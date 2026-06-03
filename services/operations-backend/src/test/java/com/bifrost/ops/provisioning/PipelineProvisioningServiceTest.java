package com.bifrost.ops.provisioning;

import com.bifrost.ops.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.*;
import com.bifrost.ops.provisioning.port.KafkaPipelineProvisioner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 호출 경로 위임 검증(#45). 컨트롤러가 의존하는 service가 provisioner 포트로
 * 정확히 위임하는지(구현 무관)만 본다.
 */
class PipelineProvisioningServiceTest {

    private final KafkaPipelineProvisioner provisioner = mock(KafkaPipelineProvisioner.class);
    private final PipelineProvisioningService service = new PipelineProvisioningService(provisioner);

    private PipelineProvisionCommand edaCommand(UUID id) {
        return new PipelineProvisionCommand(
                id, "demo", PipelinePattern.FAN_OUT,
                new PipelineProvisionCommand.Endpoint(
                        DbType.POSTGRESQL, "db", 5432, "app", "public", "orders", "secret://src"),
                null);
    }

    @Test
    void provisionDelegatesToProvisioner() {
        UUID id = UUID.randomUUID();
        PipelineProvisionResult expected = PipelineProvisionResult.success(
                id, List.of(new PipelineProvisionResult.ConnectorRef(
                        "demo-src", ConnectorKind.SOURCE, "io.debezium...")), "demo.app");
        when(provisioner.createPipelineResources(any())).thenReturn(expected);

        PipelineProvisionResult result = service.provision(edaCommand(id));

        assertThat(result).isSameAs(expected);
        verify(provisioner).createPipelineResources(any(PipelineProvisionCommand.class));
    }

    @Test
    void statusDelegatesToProvisioner() {
        PipelineProvisionStatus expected = new PipelineProvisionStatus("demo-src", "RUNNING", List.of());
        when(provisioner.getConnectorStatus("proj", "demo-src")).thenReturn(expected);

        assertThat(service.status("proj", "demo-src")).isSameAs(expected);
        verify(provisioner).getConnectorStatus("proj", "demo-src");
    }

    @Test
    void deleteDelegatesToProvisioner() {
        PipelineResourceRef ref = new PipelineResourceRef(
                UUID.randomUUID(), "platform-kafka", List.of("demo-src"));

        service.delete(ref);

        verify(provisioner).deletePipelineResources(ref);
    }
}
