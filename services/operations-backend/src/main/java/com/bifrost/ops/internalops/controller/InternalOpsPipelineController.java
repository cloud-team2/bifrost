package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.PipelineTopologyResult;
import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Agent read tool — pipeline 목록 및 topology.
 *
 * <p>project_id = workspace.namespace 로 workspace를 조회한 뒤 pipeline 데이터를 읽는다.
 * JWT 없이 접근 가능(/internal/ops/** permitAll).
 */
@RestController
@RequestMapping("/internal/ops/projects/{projectId}/pipelines")
public class InternalOpsPipelineController {

    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;

    public InternalOpsPipelineController(WorkspaceRepository workspaceRepository,
                                         PipelineRepository pipelineRepository,
                                         ConnectorRepository connectorRepository) {
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
    }

    /** list_project_pipelines — 프로젝트(workspace) 기준 pipeline 목록. */
    @GetMapping
    public ResponseEntity<OpsEnvelope<List<PipelineResponse>>> listPipelines(
            @PathVariable String projectId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity ws = requireWorkspace(projectId);
        List<PipelineResponse> pipelines = pipelineRepository
                .findByTenantIdOrderByCreatedAtDesc(ws.getId())
                .stream()
                .map(PipelineResponse::from)
                .toList();
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "list_project_pipelines", pipelines));
    }

    /** get_pipeline_topology — source/sink/connectors/topics/status. */
    @GetMapping("/{pipelineId}/topology")
    public ResponseEntity<OpsEnvelope<PipelineTopologyResult>> topology(
            @PathVariable String projectId,
            @PathVariable UUID pipelineId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity ws = requireWorkspace(projectId);

        PipelineResponse pipeline = pipelineRepository
                .findByIdAndTenantId(pipelineId, ws.getId())
                .map(PipelineResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND, "파이프라인을 찾을 수 없습니다"));

        List<ConnectorResponse> connectors = connectorRepository
                .findByPipelineId(pipelineId)
                .stream()
                .map(ConnectorResponse::from)
                .toList();

        PipelineTopologyResult result = PipelineTopologyResult.of(pipeline, connectors);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_pipeline_topology", result));
    }

    private WorkspaceEntity requireWorkspace(String projectId) {
        return WorkspaceLookup.resolve(workspaceRepository, projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
    }
}
