package com.bifrost.ops.provisioning.mock;

import com.bifrost.ops.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.ProvisionStage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** mock provisioner가 CR 없이 naming 기반 성공 결과를 반환하는지 검증(#16). */
class MockKafkaPipelineProvisionerTest {

    private final MockKafkaPipelineProvisioner provisioner = new MockKafkaPipelineProvisioner();

    private PipelineProvisionCommand.Endpoint source() {
        return new PipelineProvisionCommand.Endpoint(
                DbType.POSTGRESQL, "h", 5432, "shop", "public", "orders", "secret://src");
    }

    @Test
    void fanOutReturnsSingleSourceConnector() {
        PipelineProvisionCommand cmd = new PipelineProvisionCommand(
                UUID.randomUUID(), "team2", PipelinePattern.FAN_OUT, source(), null);

        PipelineProvisionResult result = provisioner.createPipelineResources(cmd);

        assertThat(result.success()).isTrue();
        assertThat(result.stage()).isEqualTo(ProvisionStage.COMPLETED);
        assertThat(result.connectors()).hasSize(1);
        assertThat(result.connectors().get(0).kind()).isEqualTo(ConnectorKind.SOURCE);
        assertThat(result.topicPrefix()).isEqualTo("cdc.table.team2.shop");
    }

    @Test
    void directReturnsSourceAndSinkConnectors() {
        PipelineProvisionCommand.Endpoint sink = new PipelineProvisionCommand.Endpoint(
                DbType.POSTGRESQL, "h2", 5432, "warehouse", null, null, "secret://sink");
        PipelineProvisionCommand cmd = new PipelineProvisionCommand(
                UUID.randomUUID(), "team2", PipelinePattern.DIRECT, source(), sink);

        PipelineProvisionResult result = provisioner.createPipelineResources(cmd);

        assertThat(result.success()).isTrue();
        assertThat(result.connectors()).hasSize(2);
        assertThat(result.connectors())
                .extracting(PipelineProvisionResult.ConnectorRef::kind)
                .containsExactlyInAnyOrder(ConnectorKind.SOURCE, ConnectorKind.SINK);
    }

    @Test
    void getConnectorStatusReturnsRunning() {
        var status = provisioner.getConnectorStatus("team2", "pipe-source");
        assertThat(status.connectorState()).isEqualTo("RUNNING");
        assertThat(status.tasks()).hasSize(1);
    }
}
