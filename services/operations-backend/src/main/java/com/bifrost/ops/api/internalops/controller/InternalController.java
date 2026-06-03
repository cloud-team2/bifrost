package com.bifrost.ops.api.internalops.controller;

import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;
import com.bifrost.ops.provisioning.impl.strimzi.TenantProvisioner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 내부(control-plane) 운영 API.
 *
 * <p>pipeline 생성/조회/삭제는 {@link PipelineProvisioningService}를 거쳐
 * {@code KafkaPipelineProvisioner} 포트로 위임된다. {@code provisioning.mode}에 따라
 * mock/real 구현이 바뀌어도 컨트롤러는 그대로다(#45).
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final TenantProvisioner tenantProvisioner;
    private final PipelineProvisioningService pipelineService;

    public InternalController(TenantProvisioner tenantProvisioner,
                              PipelineProvisioningService pipelineService) {
        this.tenantProvisioner = tenantProvisioner;
        this.pipelineService = pipelineService;
    }

    @PostMapping("/tenants/provision")
    public ResponseEntity<TenantProvisionResponse> provisionTenant(
            @RequestBody TenantProvisionRequest req) {
        return ResponseEntity.ok(tenantProvisioner.provision(req));
    }

    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<Void> deprovisionTenant(@PathVariable UUID tenantId) {
        tenantProvisioner.deprovision(tenantId);
        return ResponseEntity.accepted().build();
    }

    /**
     * 파이프라인 리소스 생성. 성공 시 202 Accepted(실제 RUNNING은 watcher가 비동기로 반영),
     * 부분 실패 시 422 Unprocessable Entity로 stage/errorCode를 담아 반환한다.
     */
    @PostMapping("/pipelines")
    public ResponseEntity<PipelineProvisionResult> createPipeline(
            @RequestBody PipelineProvisionCommand command) {
        PipelineProvisionResult result = pipelineService.provision(command);
        HttpStatus status = result.success() ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/pipelines/status")
    public ResponseEntity<PipelineProvisionStatus> getStatus(@RequestParam String projectId,
                                                             @RequestParam String connectorName) {
        return ResponseEntity.ok(pipelineService.status(projectId, connectorName));
    }

    @DeleteMapping("/pipelines")
    public ResponseEntity<Void> deletePipeline(@RequestBody PipelineResourceRef resourceRef) {
        pipelineService.delete(resourceRef);
        return ResponseEntity.accepted().build();
    }
}
