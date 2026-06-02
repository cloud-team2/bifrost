package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineStatusService;
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

/**
 * KafkaConnector CR 상태를 watch해 {@link PipelineStatusService}로 전달하는 watcher(설계 §6, FR-008).
 *
 * <p>{@code strimzi.io/cluster=platform-connect} 라벨이 붙은 KafkaConnector의 상태 변화를 받아
 * {@link ConnectorStateMapper}로 (connector 상태, pipeline 상태)를 매핑한 뒤
 * {@link PipelineStatusService#applyConnectorStatus}만 호출한다. pipeline row는 직접 수정하지 않는다.
 *
 * <p>스왑/의존: {@code provisioning.mode=real}일 때만 활성화한다(기본 mock에서는 watch 불필요).
 * {@link PipelineStatusService} 구현(권세빈)이 있어야 빈이 주입된다.
 *
 * <p>스켈레톤 범위: watch 등록/해제와 이벤트→매핑→서비스 호출 경로를 구현한다. 재구독 백오프,
 * resourceVersion 재동기화 등 견고화는 real 연동(목요일)에서 보강한다.
 */
@Component
@ConditionalOnProperty(name = "provisioning.mode", havingValue = "real")
public class KafkaConnectorWatcher {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnectorWatcher.class);
    private static final String CLUSTER_LABEL = "strimzi.io/cluster";

    private final KubernetesClient k8s;
    private final PipelineStatusService statusService;
    private final ConnectorStateMapper mapper;
    private final String namespace;
    private final String connectCluster;

    private Watch watch;

    public KafkaConnectorWatcher(
            KubernetesClient k8s,
            PipelineStatusService statusService,
            ConnectorStateMapper mapper,
            @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
            @Value("${kafka-connect.cluster:platform-connect}") String connectCluster) {
        this.k8s = k8s;
        this.statusService = statusService;
        this.mapper = mapper;
        this.namespace = namespace;
        this.connectCluster = connectCluster;
    }

    /** 애플리케이션 기동 완료 후 watch를 등록한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("KafkaConnectorWatcher 시작: namespace={}, cluster={}", namespace, connectCluster);
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
                        // TODO(#13 real 마감): 백오프 후 재구독, resourceVersion 재동기화
                        log.warn("KafkaConnector watch 종료: {}",
                                cause != null ? cause.getMessage() : "정상 종료");
                    }
                });
    }

    /**
     * watch 이벤트 1건을 처리한다(매핑 → 단일 writer 호출). 테스트에서 직접 호출 가능하도록 분리.
     */
    void handleEvent(Watcher.Action action, KafkaConnector resource) {
        if (action == Watcher.Action.DELETED || resource == null) {
            return; // 삭제는 pipeline delete 흐름이 별도 처리
        }
        ConnectorStatusUpdate update = mapper.map(resource);
        log.debug("connector 상태 변경 감지: name={}, connectorState={}, pipelineStatus={}",
                update.connectorName(), update.connectorState(), update.pipelineStatus());
        statusService.applyConnectorStatus(update);
    }

    @PreDestroy
    public void stop() {
        if (watch != null) {
            watch.close();
            log.info("KafkaConnectorWatcher 종료");
        }
    }
}
