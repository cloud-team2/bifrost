package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.provisioning.persistence.ConnectorStatusSink;
import io.fabric8.kubernetes.client.Watcher;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * watcher가 이벤트를 매핑해 connector 메타 sink + PipelineStatusService(단일 writer) 두 경로로
 * 전달하는지, 재구독 가드가 종료 상태를 존중하는지 검증(#13/#46).
 */
class KafkaConnectorWatcherTest {

    private KafkaConnector running() {
        KafkaConnector cr = new KafkaConnectorBuilder()
                .withNewMetadata().withName("pipe-source").endMetadata().build();
        KafkaConnectorStatus status = new KafkaConnectorStatus();
        status.setConnectorStatus(Map.of(
                "connector", Map.of("state", "RUNNING"),
                "tasks", List.of(Map.of("id", 0, "state", "RUNNING"))));
        cr.setStatus(status);
        return cr;
    }

    private KafkaConnectorWatcher watcher(ConnectorStatusSink sink, PipelineStatusService service) {
        return new KafkaConnectorWatcher(
                null, sink, service, new ConnectorStateMapper(), "platform-kafka", "platform-connect");
    }

    /** applyConnectorStatus만 캡처하고 나머지는 no-op인 PipelineStatusService 어댑터(다중 메서드라 람다 불가). */
    private static PipelineStatusService statusService(java.util.function.Consumer<ConnectorStatusUpdate> onApply) {
        return new PipelineStatusService() {
            @Override public void applyConnectorStatus(ConnectorStatusUpdate u) { onApply.accept(u); }
            @Override public int failTimedOutCreating(java.time.Duration t) { return 0; }
            @Override public void reevaluateForDatasource(java.util.UUID id) { }
        };
    }

    @Test
    void delegatesMappedUpdateToSinkAndStatusService() {
        AtomicReference<ConnectorStatusUpdate> sinkCaptured = new AtomicReference<>();
        AtomicReference<ConnectorStatusUpdate> serviceCaptured = new AtomicReference<>();

        KafkaConnectorWatcher watcher = watcher(sinkCaptured::set, statusService(serviceCaptured::set));
        watcher.handleEvent(Watcher.Action.MODIFIED, running());

        assertThat(sinkCaptured.get()).isNotNull();
        assertThat(sinkCaptured.get().connectorName()).isEqualTo("pipe-source");
        assertThat(serviceCaptured.get()).isNotNull();
        assertThat(serviceCaptured.get().pipelineStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
    }

    @Test
    void ignoresDeleteEvents() {
        AtomicReference<ConnectorStatusUpdate> sinkCaptured = new AtomicReference<>();
        AtomicReference<ConnectorStatusUpdate> serviceCaptured = new AtomicReference<>();

        KafkaConnectorWatcher watcher = watcher(sinkCaptured::set, statusService(serviceCaptured::set));
        watcher.handleEvent(Watcher.Action.DELETED, running());

        assertThat(sinkCaptured.get()).isNull();
        assertThat(serviceCaptured.get()).isNull();
    }

    @Test
    void sinkFailureDoesNotBlockStatusService() {
        AtomicReference<ConnectorStatusUpdate> serviceCaptured = new AtomicReference<>();
        ConnectorStatusSink failingSink = u -> { throw new RuntimeException("db down"); };

        KafkaConnectorWatcher watcher = watcher(failingSink, statusService(serviceCaptured::set));
        watcher.handleEvent(Watcher.Action.MODIFIED, running());

        // 메타 sink가 실패해도 pipeline status writer는 호출되어야 한다
        assertThat(serviceCaptured.get()).isNotNull();
        assertThat(serviceCaptured.get().connectorName()).isEqualTo("pipe-source");
    }

    @Test
    void doesNotReconnectAfterStop() {
        KafkaConnectorWatcher watcher = watcher(u -> {}, statusService(u -> {}));

        assertThat(watcher.shouldReconnect()).isTrue();
        watcher.stop();
        assertThat(watcher.shouldReconnect()).isFalse();
        // 종료 후 onClose가 와도 재구독을 시도하지 않는다(null k8s NPE 없이 무사 반환)
        watcher.handleClose(null);
    }

    @Test
    void healthyEventResetsBackoff() {
        KafkaConnectorWatcher watcher = watcher(u -> {}, statusService(u -> {}));
        watcher.handleEvent(Watcher.Action.MODIFIED, running());
        assertThat(watcher.currentBackoffMs()).isEqualTo(1_000L);
    }
}
