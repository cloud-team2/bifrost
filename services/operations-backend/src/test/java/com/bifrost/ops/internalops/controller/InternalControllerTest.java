package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.*;
import com.bifrost.ops.provisioning.impl.strimzi.TenantProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * createPipeline의 성공/부분실패 → HTTP 상태 매핑 검증(#45).
 * Spring 컨텍스트 없이 컨트롤러를 직접 호출해 매핑 로직만 본다.
 */
class InternalControllerTest {

    private final TenantProvisioner tenantProvisioner = mock(TenantProvisioner.class);
    private final PipelineProvisioningService pipelineService = mock(PipelineProvisioningService.class);
    private final InternalController controller =
            new InternalController(tenantProvisioner, pipelineService);

    private PipelineProvisionCommand command(UUID id) {
        return new PipelineProvisionCommand(
                id, "demo", PipelinePattern.FAN_OUT,
                new PipelineProvisionCommand.Endpoint(
                        DbType.POSTGRESQL, "db", 5432, "app", UUID.randomUUID(), "public", "orders", "secret://src"),
                null);
    }

    @Test
    void createPipelineReturns202OnSuccess() {
        UUID id = UUID.randomUUID();
        when(pipelineService.provision(any())).thenReturn(
                PipelineProvisionResult.success(id, List.of(), "demo.app"));

        ResponseEntity<PipelineProvisionResult> resp = controller.createPipeline(command(id));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().success()).isTrue();
    }

    @Test
    void createPipelineReturns422OnPartialFailure() {
        UUID id = UUID.randomUUID();
        when(pipelineService.provision(any())).thenReturn(
                PipelineProvisionResult.failure(
                        id, ProvisionStage.SOURCE_CONNECTOR, List.of(), "demo.app",
                        "SOURCE_CONNECTOR_FAILED", "source apply 실패"));

        ResponseEntity<PipelineProvisionResult> resp = controller.createPipeline(command(id));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().success()).isFalse();
        assertThat(resp.getBody().stage()).isEqualTo(ProvisionStage.SOURCE_CONNECTOR);
    }

    @Test
    void deletePipelineReturns202() {
        ResponseEntity<Void> resp = controller.deletePipeline(
                new PipelineResourceRef(UUID.randomUUID(), "platform-kafka", List.of("demo-src")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(pipelineService).delete(any(PipelineResourceRef.class));
    }
}
