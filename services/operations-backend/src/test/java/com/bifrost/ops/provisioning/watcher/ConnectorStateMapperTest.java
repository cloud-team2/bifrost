package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** connector 상태 → pipeline 상태 매핑 규칙(#13, 설계 §6/부록 B.2) 단위 테스트. */
class ConnectorStateMapperTest {

    private final ConnectorStateMapper mapper = new ConnectorStateMapper();

    private KafkaConnector connector(String connectorState, List<Map<String, Object>> tasks) {
        KafkaConnector cr = new KafkaConnectorBuilder()
                .withNewMetadata().withName("pipe-source").endMetadata()
                .build();
        KafkaConnectorStatus status = new KafkaConnectorStatus();
        status.setConnectorStatus(Map.of(
                "connector", Map.of("state", connectorState),
                "tasks", tasks));
        cr.setStatus(status);
        return cr;
    }

    @Test
    void runningAllTasksMapsToActive() {
        ConnectorStatusUpdate u = mapper.map(connector("RUNNING",
                List.of(Map.of("id", 0, "state", "RUNNING"))));
        assertThat(u.connectorState()).isEqualTo(ConnectorRuntimeState.RUNNING);
        assertThat(u.pipelineStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
        assertThat(u.failedTasks()).isZero();
    }

    @Test
    void runningWithFailedTaskMapsToPartiallyFailedLag() {
        ConnectorStatusUpdate u = mapper.map(connector("RUNNING", List.of(
                Map.of("id", 0, "state", "RUNNING"),
                Map.of("id", 1, "state", "FAILED", "trace", "boom"))));
        assertThat(u.connectorState()).isEqualTo(ConnectorRuntimeState.PARTIALLY_FAILED);
        assertThat(u.pipelineStatus()).isEqualTo(PipelineLifecycle.LAG);
        assertThat(u.totalTasks()).isEqualTo(2);
        assertThat(u.failedTasks()).isEqualTo(1);
        assertThat(u.lastError()).isEqualTo("boom");
    }

    @Test
    void failedConnectorMapsToError() {
        ConnectorStatusUpdate u = mapper.map(connector("FAILED",
                List.of(Map.of("id", 0, "state", "FAILED", "trace", "connector failed"))));
        assertThat(u.connectorState()).isEqualTo(ConnectorRuntimeState.FAILED);
        assertThat(u.pipelineStatus()).isEqualTo(PipelineLifecycle.ERROR);
    }

    @Test
    void longTracePreservesRootCauseWhenTruncated() {
        // (#692) head-cut만 하면 root cause('Caused by: ... Connection refused')가 잘려나가
        // DB 연결 실패 dedup 판별이 실패한다. 잘려도 root cause 라인은 보존되어야 한다.
        String head = "org.apache.kafka.connect.errors.ConnectException: "
                + "Exiting WorkerSinkTask due to unrecoverable exception.\n"
                + "\tat org.apache.kafka.connect.runtime.WorkerSinkTask.deliverMessages(WorkerSinkTask.java:658)\n".repeat(8);
        String trace = head
                + "Caused by: java.sql.SQLNonTransientConnectionException: "
                + "Socket fail to connect to address=(host=host.docker.internal)(port=3307). Connection refused\n"
                + "\tat org.mariadb.jdbc.client.impl.ConnectionHelper.connectSocket(ConnectionHelper.java:120)\n";

        ConnectorStatusUpdate u = mapper.map(connector("FAILED",
                List.of(Map.of("id", 0, "state", "FAILED", "trace", trace))));

        assertThat(u.lastError()).contains("Connection refused");
        assertThat(com.bifrost.ops.pipeline.status.ConnectorErrorMessages.isDbConnectionFailure(u.lastError())).isTrue();
    }

    @Test
    void pausedConnectorMapsToPaused() {
        ConnectorStatusUpdate u = mapper.map(connector("PAUSED", List.of()));
        assertThat(u.connectorState()).isEqualTo(ConnectorRuntimeState.PAUSED);
        assertThat(u.pipelineStatus()).isEqualTo(PipelineLifecycle.PAUSED);
    }

    @Test
    void unassignedMapsToCreating() {
        ConnectorStatusUpdate u = mapper.map(connector("UNASSIGNED", List.of()));
        assertThat(u.connectorState()).isEqualTo(ConnectorRuntimeState.UNASSIGNED);
        assertThat(u.pipelineStatus()).isEqualTo(PipelineLifecycle.CREATING);
    }

    @Test
    void noStatusMapsToUnknownCreating() {
        KafkaConnector cr = new KafkaConnectorBuilder()
                .withNewMetadata().withName("pipe-source").endMetadata().build();
        ConnectorStatusUpdate u = mapper.map(cr);
        assertThat(u.connectorState()).isEqualTo(ConnectorRuntimeState.UNKNOWN);
        assertThat(u.pipelineStatus()).isEqualTo(PipelineLifecycle.CREATING);
    }
}
