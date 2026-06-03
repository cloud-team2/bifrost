package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.provisioning.persistence.ConnectorStatusSink;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * KafkaConnector CR 상태를 watch해 상태를 반영하는 watcher(설계 §6, FR-008).
 *
 * <p>{@code strimzi.io/cluster=platform-connect} 라벨이 붙은 KafkaConnector의 상태 변화를 받아
 * {@link ConnectorStateMapper}로 (connector 상태, pipeline 상태)를 매핑한 뒤 두 경로로 전달한다:
 * <ul>
 *   <li>{@link ConnectorStatusSink} — connectors 메타 행(state/last_error/updated_at) 갱신(#46).</li>
 *   <li>{@link PipelineStatusService#applyConnectorStatus} — pipeline row 단일 writer(권세빈).</li>
 * </ul>
 * pipeline row는 직접 수정하지 않는다.
 *
 * <p>재구독(#46): watch가 끊기면(onClose, abnormal) 지수 백오프로 재등록한다. 정상 이벤트 수신 시
 * 백오프를 리셋하고, 종료({@link #stop()}) 중에는 재구독하지 않는다.
 *
 * <p>스왑/의존: {@code provisioning.mode=real}일 때만 활성화한다(기본 mock에서는 watch 불필요).
 */
@Component
@ConditionalOnProperty(name = "provisioning.mode", havingValue = "real")
public class KafkaConnectorWatcher {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnectorWatcher.class);
    private static final String CLUSTER_LABEL = "strimzi.io/cluster";

    private static final long BASE_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final KubernetesClient k8s;
    private final ConnectorStatusSink statusSink;
    private final PipelineStatusService statusService;
    private final ConnectorStateMapper mapper;
    private final String namespace;
    private final String connectCluster;

    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kafkaconnector-watch-reconnect");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean closed = false;
    private volatile long currentBackoffMs = BASE_BACKOFF_MS;
    private volatile Watch watch;

    public KafkaConnectorWatcher(
            KubernetesClient k8s,
            ConnectorStatusSink statusSink,
            PipelineStatusService statusService,
            ConnectorStateMapper mapper,
            @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
            @Value("${kafka-connect.cluster:platform-connect}") String connectCluster) {
        this.k8s = k8s;
        this.statusSink = statusSink;
        this.statusService = statusService;
        this.mapper = mapper;
        this.namespace = namespace;
        this.connectCluster = connectCluster;
    }

    /** 애플리케이션 기동 완료 후 watch를 등록한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("KafkaConnectorWatcher 시작: namespace={}, cluster={}", namespace, connectCluster);
        register();
    }

    /** watch를 (재)등록한다. 실패 시 호출부가 백오프 재시도를 스케줄한다. */
    void register() {
        this.watch = k8s.resources(KafkaConnector.class)
                .inNamespace(namespace)
                .withLabel(CLUSTER_LABEL, connectCluster)
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(Action action, KafkaConnector resource) {
                        handleEvent(action, resource);
                    }

                    @Override
                    public void onClose(WatcherException cause) {
                        handleClose(cause);
                    }
                });
    }

    /**
     * watch 이벤트 1건을 처리한다(매핑 → 메타 sink + pipeline writer). 정상 수신이므로 백오프 리셋.
     * 테스트에서 직접 호출 가능하도록 분리.
     */
    void handleEvent(Watcher.Action action, KafkaConnector resource) {
        resetBackoff();
        if (action == Watcher.Action.DELETED || resource == null) {
            return; // 삭제는 pipeline delete 흐름이 별도 처리
        }
        ConnectorStatusUpdate update = mapper.map(resource);
        log.debug("connector 상태 변경 감지: name={}, connectorState={}, pipelineStatus={}",
                update.connectorName(), update.connectorState(), update.pipelineStatus());
        // connectors 메타 갱신 실패가 pipeline 상태 통지를 막지 않도록 분리해서 보호
        try {
            statusSink.record(update);
        } catch (RuntimeException e) {
            log.warn("connector 메타 반영 실패(무시): name={}, cause={}",
                    update.connectorName(), e.getClass().getSimpleName());
        }
        statusService.applyConnectorStatus(update);
    }

    /** watch 종료 처리: 종료 중이 아니면 백오프 후 재구독을 스케줄한다. 테스트용으로 분리. */
    void handleClose(WatcherException cause) {
        log.warn("KafkaConnector watch 종료: {}",
                cause != null ? cause.getMessage() : "정상 종료");
        if (!shouldReconnect()) {
            return;
        }
        long delay = currentBackoffMs;
        advanceBackoff();
        log.info("KafkaConnector watch {}ms 후 재구독 시도", delay);
        reconnectExecutor.schedule(this::reestablish, delay, TimeUnit.MILLISECONDS);
    }

    private void reestablish() {
        if (!shouldReconnect()) {
            return;
        }
        try {
            register();
            resetBackoff();
            log.info("KafkaConnector watch 재구독 성공");
        } catch (RuntimeException e) {
            long delay = currentBackoffMs;
            advanceBackoff();
            log.warn("KafkaConnector watch 재구독 실패, {}ms 후 재시도: {}", delay, e.getMessage());
            reconnectExecutor.schedule(this::reestablish, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** 종료 중이 아니면 재구독한다. */
    boolean shouldReconnect() {
        return !closed;
    }

    void resetBackoff() {
        currentBackoffMs = BASE_BACKOFF_MS;
    }

    private void advanceBackoff() {
        currentBackoffMs = Math.min(currentBackoffMs * 2, MAX_BACKOFF_MS);
    }

    long currentBackoffMs() {
        return currentBackoffMs;
    }

    @PreDestroy
    public void stop() {
        closed = true;
        reconnectExecutor.shutdownNow();
        if (watch != null) {
            watch.close();
            log.info("KafkaConnectorWatcher 종료");
        }
    }
}
