package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.DatasourceListResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agent read tool — datasource(DB) 목록과 헬스(#633). project_id = workspace 로 스코프.
 *
 * <p>'데이터베이스 현황' 질의에 답할 도구가 없어 에이전트가 파이프라인/커넥터로 대체하던 공백을 메운다.
 * 사용자 JWT 대상은 아니며, internal.ops.token 설정 시 X-Internal-Token service identity가 필요하다.
 */
@RestController
@RequestMapping("/internal/ops/projects/{projectId}/datasources")
public class InternalOpsDatasourceController {

    private final WorkspaceRepository workspaceRepository;
    private final DatasourceRepository datasourceRepository;

    public InternalOpsDatasourceController(WorkspaceRepository workspaceRepository,
                                           DatasourceRepository datasourceRepository) {
        this.workspaceRepository = workspaceRepository;
        this.datasourceRepository = datasourceRepository;
    }

    /** list_datasources — 프로젝트의 datasource 목록 + connection/readiness/role 요약. */
    @GetMapping
    public ResponseEntity<OpsEnvelope<DatasourceListResult>> listDatasources(
            @PathVariable String projectId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity ws;
        try {
            ws = requireWorkspace(projectId);
        } catch (ApiException e) {
            return ResponseEntity.ok(OpsEnvelope.error(requestId, "list_datasources", e.getMessage()));
        }
        Set<UUID> sourceIds = new HashSet<>(datasourceRepository.findSourceDatasourceIds(ws.getId()));
        Set<UUID> sinkIds = new HashSet<>(datasourceRepository.findSinkDatasourceIds(ws.getId()));
        List<DatasourceListResult.DatasourceSummary> rows = datasourceRepository
                .findByTenantIdOrderByCreatedAtDesc(ws.getId())
                .stream()
                .map(d -> toSummary(d, sourceIds, sinkIds))
                .toList();
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "list_datasources",
                new DatasourceListResult(rows)));
    }

    private static DatasourceListResult.DatasourceSummary toSummary(
            DatasourceEntity d, Set<UUID> sourceIds, Set<UUID> sinkIds) {
        return new DatasourceListResult.DatasourceSummary(
                d.getId().toString(),
                d.getName(),
                d.getDbType() == null ? null : d.getDbType().name(),
                d.getHost(),
                d.getPort(),
                role(d.getId(), sourceIds, sinkIds),
                d.getConnectionStatus(),
                d.getCdcReadinessStatus(),
                d.getSinkReadinessStatus());
    }

    private static String role(UUID id, Set<UUID> sourceIds, Set<UUID> sinkIds) {
        boolean isSource = sourceIds.contains(id);
        boolean isSink = sinkIds.contains(id);
        if (isSource && isSink) return "source+sink";
        if (isSource) return "source";
        if (isSink) return "sink";
        return "unused";
    }

    private WorkspaceEntity requireWorkspace(String projectId) {
        return WorkspaceLookup.resolve(workspaceRepository, projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
    }
}
