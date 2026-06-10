package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;
import com.bifrost.ops.provisioning.impl.strimzi.TenantProvisioner;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 내부(control-plane) 운영 API.
 *
 * <p>pipeline 생성/조회/삭제는 {@link PipelineProvisioningService}를 거쳐
 * {@code KafkaPipelineProvisioner} 포트(Strimzi 구현)로 위임된다(#45).
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final TenantProvisioner tenantProvisioner;
    private final PipelineProvisioningService pipelineService;
    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;

    public InternalController(TenantProvisioner tenantProvisioner,
                              PipelineProvisioningService pipelineService,
                              WorkspaceRepository workspaceRepository,
                              PipelineRepository pipelineRepository,
                              ConnectorRepository connectorRepository) {
        this.tenantProvisioner = tenantProvisioner;
        this.pipelineService = pipelineService;
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
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

    /**
     * Canonical connector 상태 조회 (설계 §6, smoke script 및 Agent read tool 계약).
     * 공통 OpsEnvelope로 감싸 FastAPI get_connector_status tool과 계약 일치.
     */
    @GetMapping("/ops/projects/{projectId}/kafka/connectors/{connectorName}/status")
    public ResponseEntity<OpsEnvelope<PipelineProvisionStatus>> getConnectorStatus(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            requireOwnedConnector(projectId, connectorName);
        } catch (ApiException e) {
            return connectorStatusApiError(requestId, e);
        }
        PipelineProvisionStatus status = pipelineService.status(projectId, connectorName);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_connector_status", status));
    }

    @DeleteMapping("/pipelines")
    public ResponseEntity<Void> deletePipeline(@RequestBody PipelineResourceRef resourceRef) {
        pipelineService.delete(resourceRef);
        return ResponseEntity.accepted().build();
    }

    private ConnectorEntity requireOwnedConnector(String projectId, String connectorName) {
        WorkspaceEntity workspace = WorkspaceLookup.resolve(workspaceRepository, projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
        ConnectorEntity connector = connectorRepository.findByCrName(connectorName)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "connector not found: " + connectorName));
        pipelineRepository.findByIdAndTenantId(connector.getPipelineId(), workspace.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_FORBIDDEN,
                        "connector is not owned by project: " + projectId));
        return connector;
    }

    private static ResponseEntity<OpsEnvelope<PipelineProvisionStatus>> connectorStatusApiError(
            String requestId,
            ApiException e) {
        if (e.code() == ErrorCode.WORKSPACE_FORBIDDEN) {
            return ResponseEntity.status(ErrorCode.WORKSPACE_FORBIDDEN.status())
                    .body(OpsEnvelope.error(requestId, "get_connector_status",
                            "RESOURCE_NOT_OWNED_BY_PROJECT", e.getMessage(), false, "check_project_scope"));
        }
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status())
                .body(OpsEnvelope.error(requestId, "get_connector_status",
                        "RESOURCE_NOT_FOUND", e.getMessage(), false, "check_project_scope"));
    }
}
