package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelineStatusService;
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
 * watcher가 이벤트를 매핑해 PipelineStatusService(단일 writer)로만 전달하는지 검증(#13).
 * pipeline row를 직접 수정하지 않고 서비스 호출만 하는 경계를 확인한다.
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

    @Test
    void delegatesMappedUpdateToStatusService() {
        AtomicReference<ConnectorStatusUpdate> captured = new AtomicReference<>();
        PipelineStatusService service = captured::set;

        KafkaConnectorWatcher watcher = new KafkaConnectorWatcher(
                null, service, new ConnectorStateMapper(), "platform-kafka", "platform-connect");

        watcher.handleEvent(Watcher.Action.MODIFIED, running());

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().connectorName()).isEqualTo("pipe-source");
        assertThat(captured.get().pipelineStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
    }

    @Test
    void ignoresDeleteEvents() {
        AtomicReference<ConnectorStatusUpdate> captured = new AtomicReference<>();
        PipelineStatusService service = captured::set;

        KafkaConnectorWatcher watcher = new KafkaConnectorWatcher(
                null, service, new ConnectorStateMapper(), "platform-kafka", "platform-connect");

        watcher.handleEvent(Watcher.Action.DELETED, running());

        assertThat(captured.get()).isNull();
    }
}
