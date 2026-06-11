package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.PipelineStatusListResult;
import com.bifrost.ops.internalops.dto.PipelineTopologyResult;
import com.bifrost.ops.internalops.dto.RecentChangesResult;
import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent read tool — pipeline 목록 및 topology.
 *
 * <p>project_id = workspace.namespace 로 workspace를 조회한 뒤 pipeline 데이터를 읽는다.
 * JWT 없이 접근 가능(/internal/ops/** permitAll).
 */
@RestController
@RequestMapping("/internal/ops/projects/{projectId}/pipelines")
public class InternalOpsPipelineController {

    private static final long ADMIN_TIMEOUT_SEC = 5L;

    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final AdminClient adminClient;

    public InternalOpsPipelineController(WorkspaceRepository workspaceRepository,
                                         PipelineRepository pipelineRepository,
                                         ConnectorRepository connectorRepository,
                                         AdminClient adminClient) {
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.adminClient = adminClient;
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

    /** list_pipelines — 프로젝트 파이프라인 상태와 sink consumer lag 요약. */
    @GetMapping("/status")
    public ResponseEntity<OpsEnvelope<PipelineStatusListResult>> listPipelineStatuses(
            @PathVariable String projectId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity ws;
        try {
            ws = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, "list_pipelines", e);
        }
        List<PipelineStatusListResult.PipelineStatusSummary> pipelines = pipelineRepository
                .findByTenantIdOrderByCreatedAtDesc(ws.getId())
                .stream()
                .map(this::pipelineStatusSummary)
                .toList();
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "list_pipelines",
                new PipelineStatusListResult(pipelines)));
    }

    /**
     * get_recent_changes(ai-service get_deployments) — 프로젝트 단위 최근 파이프라인 변경 이력.
     *
     * <p>RECENT_DEPLOY_REGRESSION root_cause 의 배포 diff evidence 수집용.
     * 현재는 pipeline lifecycle(생성·상태전이)에서 변경 이벤트를 합성한다.
     */
    @GetMapping("/changes")
    public ResponseEntity<OpsEnvelope<RecentChangesResult>> changes(
            @PathVariable String projectId,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity ws = requireWorkspace(projectId);

        List<PipelineEntity> pipelines = pipelineRepository
                .findByTenantIdOrderByCreatedAtDesc(ws.getId());

        RecentChangesResult result = RecentChangesResult.of(pipelines, limit);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_recent_changes", result));
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

    private static <T> ResponseEntity<OpsEnvelope<T>> apiError(
            String requestId,
            String operation,
            ApiException e) {
        if (e.code() == ErrorCode.WORKSPACE_FORBIDDEN) {
            return ResponseEntity.status(ErrorCode.WORKSPACE_FORBIDDEN.status())
                    .body(OpsEnvelope.error(requestId, operation, "RESOURCE_NOT_OWNED_BY_PROJECT",
                            e.getMessage(), false, "check_project_scope"));
        }
        return ResponseEntity.status(ErrorCode.WORKSPACE_NOT_FOUND.status())
                .body(OpsEnvelope.error(requestId, operation, "RESOURCE_NOT_FOUND",
                        e.getMessage(), false, "check_project_scope"));
    }

    private PipelineStatusListResult.PipelineStatusSummary pipelineStatusSummary(PipelineEntity pipeline) {
        String group = sinkConsumerGroup(pipeline);
        LagSnapshot lag = group == null ? new LagSnapshot(null, null) : fetchLagSnapshot(group);
        return new PipelineStatusListResult.PipelineStatusSummary(
                pipeline.getId().toString(),
                pipeline.getName(),
                pipeline.getStatus().name().toLowerCase(),
                lag.lag(),
                lag.error());
    }

    private String sinkConsumerGroup(PipelineEntity pipeline) {
        String sinkName = connectorRepository.findByPipelineId(pipeline.getId()).stream()
                .filter(connector -> connector.getKind() == ConnectorKind.SINK)
                .map(ConnectorEntity::getCrName)
                .findFirst()
                .orElse(pipeline.getSinkConnectorName());
        if (sinkName == null || sinkName.isBlank()) {
            return null;
        }
        return "connect-" + sinkName;
    }

    private LagSnapshot fetchLagSnapshot(String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (committed.isEmpty()) {
                return new LagSnapshot(0L, null);
            }

            Map<TopicPartition, OffsetSpec> endReqs = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = adminClient
                    .listOffsets(endReqs)
                    .all()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            long lag = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                long end = ends.containsKey(entry.getKey()) ? ends.get(entry.getKey()).offset() : 0L;
                lag += Math.max(0L, end - entry.getValue().offset());
            }
            return new LagSnapshot(lag, null);
        } catch (Exception e) {
            return new LagSnapshot(null, e.getMessage());
        }
    }

    private record LagSnapshot(Long lag, String error) {}
}
