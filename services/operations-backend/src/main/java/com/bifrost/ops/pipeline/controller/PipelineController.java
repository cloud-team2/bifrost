package com.bifrost.ops.pipeline.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.internalops.dto.TraceSummaryResult;
import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.DataplaneTracingState;
import com.bifrost.ops.pipeline.dto.ConsumerGroupInfo;
import com.bifrost.ops.pipeline.dto.KafkaMessageRecord;
import com.bifrost.ops.pipeline.dto.MessagePageResponse;
import com.bifrost.ops.pipeline.dto.ConnectionGuideResponse;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineMetricsResponse;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.dto.PipelineStageStatusResponse;
import com.bifrost.ops.pipeline.dto.EventDistPoint;
import com.bifrost.ops.pipeline.dto.MetricPoint;
import com.bifrost.ops.pipeline.dto.SyncStatusResponse;
import com.bifrost.ops.pipeline.dto.TableMappingResponse;
import com.bifrost.ops.pipeline.dto.ThroughputPoint;
import com.bifrost.ops.pipeline.dto.TopicInfoResponse;
import com.bifrost.ops.pipeline.runtime.PipelineRuntimeMetadataService;
import com.bifrost.ops.pipeline.service.PipelineMessageService;
import com.bifrost.ops.pipeline.service.PipelineService;
import com.bifrost.ops.pipeline.service.PipelineSyncService;
import com.bifrost.ops.pipeline.service.PipelineTopicService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 플랫폼 API(frontend-facing, FR-003~005). 생성은 {@code creating}으로 즉시 응답하고
 * {@code active} 전이는 비동기로 일어난다(프론트는 SSE/polling 수신, #71).
 *
 * <p>scope 검증은 {@link com.bifrost.ops.workspace.WorkspaceAccessGuard}(서비스 계층)로 일원화.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineSyncService pipelineSyncService;
    private final PipelineTopicService pipelineTopicService;
    private final PipelineMessageService pipelineMessageService;
    private final PipelineRuntimeMetadataService runtimeMetadataService;

    public PipelineController(PipelineService pipelineService,
                              PipelineSyncService pipelineSyncService,
                              PipelineTopicService pipelineTopicService,
                              PipelineMessageService pipelineMessageService,
                              PipelineRuntimeMetadataService runtimeMetadataService) {
        this.pipelineService = pipelineService;
        this.pipelineSyncService = pipelineSyncService;
        this.pipelineTopicService = pipelineTopicService;
        this.pipelineMessageService = pipelineMessageService;
        this.runtimeMetadataService = runtimeMetadataService;
    }

    /** 목록(FR-003). status 필터(creating/active/lag/error/paused). */
    @GetMapping
    public List<PipelineResponse> list(@PathVariable UUID wsId,
                                       @AuthenticationPrincipal AuthenticatedUser principal,
                                       @RequestParam(required = false) String status) {
        return pipelineService.list(wsId, principal, status);
    }

    /** 생성(FR-004). 201 + status=creating. */
    @PostMapping
    public ResponseEntity<PipelineResponse> create(@PathVariable UUID wsId,
                                                   @AuthenticationPrincipal AuthenticatedUser principal,
                                                   @Valid @RequestBody PipelineCreateRequest req) {
        return ResponseEntity.status(201).body(pipelineService.create(wsId, principal, req));
    }

    /** 상세(FR-003). source/sink/topic/connector 정보. */
    @GetMapping("/{id}")
    public PipelineResponse get(@PathVariable UUID wsId,
                                @PathVariable UUID id,
                                @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineService.get(wsId, principal, id);
    }

    /** 커넥터 목록(#107, 상세 Connector 탭). source/sink 커넥터의 state/lastError 등. */
    @GetMapping("/{id}/connectors")
    public List<ConnectorResponse> connectors(@PathVariable UUID wsId,
                                              @PathVariable UUID id,
                                              @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineService.listConnectors(wsId, principal, id);
    }

    /** 동기화 상태(#107, 상세 Sync 탭). source/sink 실제 행수 비교(CDC direct 전용). */
    @GetMapping("/{id}/sync-status")
    public SyncStatusResponse syncStatus(@PathVariable UUID wsId,
                                         @PathVariable UUID id,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineSyncService.syncStatus(wsId, principal, id);
    }

    /** 토픽 정보(#126, Overview 탭). 파티션별 offset·ISR. Kafka 미연결 시 빈 파티션 목록 반환. */
    @GetMapping("/{id}/topic-info")
    public TopicInfoResponse topicInfo(@PathVariable UUID wsId,
                                       @PathVariable UUID id,
                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineTopicService.topicInfo(wsId, principal, id);
    }

    /** 단계별(source/sink) 상태 귀속(#367, 상시 A RCA). 어느 단계가 느린지/실패인지 한눈에. */
    @GetMapping("/{id}/stage-status")
    public PipelineStageStatusResponse stageStatus(@PathVariable UUID wsId,
                                                   @PathVariable UUID id,
                                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineTopicService.stageStatus(wsId, principal, id);
    }

    /** Consumer group 목록(#126, Consumers 탭). Kafka 미연결 시 빈 목록 반환. */
    @GetMapping("/{id}/consumer-groups")
    public List<ConsumerGroupInfo> consumerGroups(@PathVariable UUID wsId,
                                                  @PathVariable UUID id,
                                                  @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineTopicService.consumerGroups(wsId, principal, id);
    }

    /** 토픽 최신 메시지(#126, Messages 탭). 전 파티션 최근 N 머지. Kafka 미연결 시 빈 목록 반환. */
    @GetMapping("/{id}/messages")
    public List<KafkaMessageRecord> messages(@PathVariable UUID wsId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal AuthenticatedUser principal,
                                             @RequestParam(defaultValue = "20") int limit) {
        return pipelineMessageService.messages(wsId, principal, id, limit);
    }

    /**
     * 단일 파티션 오프셋 페이징(#509, Messages 탭). {@code startOffset} 미지정 시 해당 파티션 최신 N.
     * begin/end offset과 hasOlder/hasNewer로 과거 메시지까지 페이지 단위로 열람한다.
     */
    @GetMapping("/{id}/messages/page")
    public MessagePageResponse messagePage(@PathVariable UUID wsId,
                                           @PathVariable UUID id,
                                           @AuthenticationPrincipal AuthenticatedUser principal,
                                           @RequestParam int partition,
                                           @RequestParam(required = false) Long startOffset,
                                           @RequestParam(defaultValue = "50") int limit) {
        return pipelineMessageService.messagePage(wsId, principal, id, partition, startOffset, limit);
    }

    /** Connection Guide(#303). bootstrap/auth/topic 정보를 비밀값 없이 반환한다. */
    @GetMapping("/{id}/connection-guide")
    public ConnectionGuideResponse connectionGuide(@PathVariable UUID wsId,
                                                   @PathVariable UUID id,
                                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        return runtimeMetadataService.connectionGuide(wsId, principal, id);
    }

    /** Table Mapping(#303). KafkaConnector config 기준 source table → topic → sink table 매핑. */
    @GetMapping("/{id}/table-mapping")
    public TableMappingResponse tableMapping(@PathVariable UUID wsId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal AuthenticatedUser principal) {
        return runtimeMetadataService.tableMapping(wsId, principal, id);
    }

    /** 파이프라인 메트릭(#126, Overview 메트릭 카드). Kafka lag + connector 에러율. */
    @GetMapping("/{id}/metrics")
    public PipelineMetricsResponse metrics(@PathVariable UUID wsId,
                                           @PathVariable UUID id,
                                           @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineTopicService.metrics(wsId, principal, id);
    }

    /** 분산 trace 요약(#498, Tracing 탭). traceId 지정 시 그 trace, 없으면 최근. */
    @GetMapping("/{id}/trace")
    public TraceSummaryResult trace(@PathVariable UUID wsId,
                                    @PathVariable UUID id,
                                    @RequestParam(required = false) String traceId,
                                    @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineTopicService.trace(wsId, principal, id, traceId);
    }

    /** 처리량 추이(#126, Overview 처리량 차트). Prometheus range query 기반 produce/consume rate 시계열. */
    @GetMapping("/{id}/metrics/throughput")
    public List<ThroughputPoint> throughput(@PathVariable UUID wsId,
                                            @PathVariable UUID id,
                                            @AuthenticationPrincipal AuthenticatedUser principal,
                                            @RequestParam(defaultValue = "30") int minutes) {
        return pipelineTopicService.throughput(wsId, principal, id, minutes);
    }

    /** 소스 지연 추이(#126, Sync 탭). Debezium MilliSecondsBehindSource 시계열(ms). */
    @GetMapping("/{id}/metrics/source-delay")
    public List<MetricPoint> sourceDelay(@PathVariable UUID wsId,
                                         @PathVariable UUID id,
                                         @AuthenticationPrincipal AuthenticatedUser principal,
                                         @RequestParam(defaultValue = "120") int minutes) {
        return pipelineTopicService.sourceDelay(wsId, principal, id, minutes);
    }

    /** 미동기화 row 추이(#126, Sync 탭). consumer lag 시계열. */
    @GetMapping("/{id}/metrics/unsynced")
    public List<MetricPoint> unsynced(@PathVariable UUID wsId,
                                      @PathVariable UUID id,
                                      @AuthenticationPrincipal AuthenticatedUser principal,
                                      @RequestParam(defaultValue = "120") int minutes) {
        return pipelineTopicService.unsynced(wsId, principal, id, minutes);
    }

    /** 이벤트 타입 분포 추이(#126, Sync 탭). Debezium create/update/delete 증가분 시계열. */
    @GetMapping("/{id}/metrics/event-distribution")
    public List<EventDistPoint> eventDistribution(@PathVariable UUID wsId,
                                                  @PathVariable UUID id,
                                                  @AuthenticationPrincipal AuthenticatedUser principal,
                                                  @RequestParam(defaultValue = "60") int minutes) {
        return pipelineTopicService.eventDistribution(wsId, principal, id, minutes);
    }

    /** 일시중지(FR-005). creating 중에는 불가. */
    @PostMapping("/{id}/pause")
    public PipelineResponse pause(@PathVariable UUID wsId,
                                  @PathVariable UUID id,
                                  @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineService.pause(wsId, principal, id);
    }

    /** 재개(FR-005). */
    @PostMapping("/{id}/resume")
    public PipelineResponse resume(@PathVariable UUID wsId,
                                   @PathVariable UUID id,
                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        return pipelineService.resume(wsId, principal, id);
    }

    /** 데이터플레인 추적 per-pipeline 토글(#438). 의심 파이프라인만 source 커넥터에 tracing SMT on/off. */
    @PostMapping("/{id}/dataplane-tracing")
    public void dataplaneTracing(@PathVariable UUID wsId,
                                 @PathVariable UUID id,
                                 @RequestParam boolean enabled,
                                 @AuthenticationPrincipal AuthenticatedUser principal) {
        pipelineService.setDataplaneTracing(wsId, principal, id, enabled);
    }

    /** 데이터플레인 추적 토글 현재 상태(#565, Tracing 탭). source 커넥터의 tracing SMT 유무. */
    @GetMapping("/{id}/dataplane-tracing")
    public DataplaneTracingState dataplaneTracingState(@PathVariable UUID wsId,
                                                       @PathVariable UUID id,
                                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        return new DataplaneTracingState(pipelineService.isDataplaneTracingEnabled(wsId, principal, id));
    }

    /**
     * 삭제(FR-005). connector CR 삭제 + 행 제거. 정상 삭제는 creating 중 불가.
     * {@code force=true}면 상태 불문 best-effort 청소(#155) — 리소스 정리가 실패해도 행은 제거.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID wsId,
                                       @PathVariable UUID id,
                                       @AuthenticationPrincipal AuthenticatedUser principal,
                                       @RequestParam(defaultValue = "false") boolean force) {
        pipelineService.delete(wsId, principal, id, force);
        return ResponseEntity.noContent().build();
    }
}
