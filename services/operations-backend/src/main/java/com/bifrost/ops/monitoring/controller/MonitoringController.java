package com.bifrost.ops.monitoring.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.event.dto.EventResponse;
import com.bifrost.ops.incident.IncidentReportService;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.incident.dto.IncidentDetailResponse;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.incident.feedback.RcaFeedbackService;
import com.bifrost.ops.incident.feedback.dto.RcaFeedbackRequest;
import com.bifrost.ops.incident.feedback.dto.RcaFeedbackResponse;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.dto.MetricsResult;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.monitoring.dto.ResourceEventResponse;
import com.bifrost.ops.monitoring.dto.SliDefinitionResponse;
import com.bifrost.ops.monitoring.dto.SliMeasurementResponse;
import com.bifrost.ops.monitoring.query.ObservabilityMetricsQuery;
import com.bifrost.ops.monitoring.service.MonitoringReadService;
import com.bifrost.ops.monitoring.sli.UserImpactSliService;
import com.bifrost.ops.monitoring.sli.UserImpactSliType;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * 모니터링 read API — overview·resource-events·incidents(S5).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/monitoring")
public class MonitoringController {

    private final MonitoringReadService monitoringReadService;
    private final IncidentService incidentService;
    private final EventService eventService;
    private final IncidentReportService incidentReportService;
    private final WorkspaceAccessGuard accessGuard;
    private final ObservabilityMetricsQuery metricsQuery;
    private final WorkspaceRepository workspaceRepository;
    private final UserImpactSliService sliService;
    private final RcaFeedbackService rcaFeedbackService;

    public MonitoringController(MonitoringReadService monitoringReadService,
                                IncidentService incidentService,
                                EventService eventService,
                                IncidentReportService incidentReportService,
                                WorkspaceAccessGuard accessGuard,
                                ObservabilityMetricsQuery metricsQuery,
                                WorkspaceRepository workspaceRepository,
                                UserImpactSliService sliService,
                                RcaFeedbackService rcaFeedbackService) {
        this.monitoringReadService = monitoringReadService;
        this.incidentService = incidentService;
        this.eventService = eventService;
        this.incidentReportService = incidentReportService;
        this.accessGuard = accessGuard;
        this.metricsQuery = metricsQuery;
        this.workspaceRepository = workspaceRepository;
        this.sliService = sliService;
        this.rcaFeedbackService = rcaFeedbackService;
    }

    /** 워크스페이스 전체 health 집계. */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview(
            @PathVariable UUID wsId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(monitoringReadService.overview(wsId));
    }

    /** KRaft rebalance·leader election 이벤트 목록. */
    @GetMapping("/resource-events")
    public ResponseEntity<List<ResourceEventResponse>> resourceEvents(
            @PathVariable UUID wsId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(monitoringReadService.resourceEvents(wsId));
    }

    /** 사용자 영향 SLI good_event/total_event 정의. */
    @GetMapping("/slis/definitions")
    public ResponseEntity<List<SliDefinitionResponse>> sliDefinitions(
            @PathVariable UUID wsId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(sliService.definitions());
    }

    /** 사용자 영향 SLI 현재 측정값 목록. */
    @GetMapping("/slis")
    public ResponseEntity<List<SliMeasurementResponse>> slis(
            @PathVariable UUID wsId,
            @RequestParam(required = false) Integer windowMinutes,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(sliService.measurements(wsId, windowMinutes));
    }

    /** 사용자 영향 SLI 단건 측정값. */
    @GetMapping("/slis/{type}")
    public ResponseEntity<SliMeasurementResponse> sli(
            @PathVariable UUID wsId,
            @PathVariable String type,
            @RequestParam(required = false) Integer windowMinutes,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(sliService.measurement(wsId, UserImpactSliType.parse(type), windowMinutes));
    }

    /** incident 목록. */
    @GetMapping("/incidents")
    public ResponseEntity<List<IncidentResponse>> incidents(
            @PathVariable UUID wsId,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(incidentService.list(wsId, status));
    }

    /** incident 상세. */
    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<IncidentResponse> incident(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(incidentService.get(wsId, incidentId));
    }

    /** incident 사용자 상태 전이(#558, 스펙 B.7): OPEN↔INVESTIGATING, →RESOLVED. */
    @PatchMapping("/incidents/{incidentId}")
    public ResponseEntity<IncidentResponse> transitionIncident(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @RequestBody com.bifrost.ops.incident.dto.IncidentStatusUpdateRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(incidentService.transitionStatus(wsId, incidentId, request.status()));
    }

    /** 지표성 인시던트 차트용 범용 시계열(#865). 카탈로그 metric을 ObservabilityMetricsQuery(PromQL)로 조회. */
    @GetMapping("/metrics/series")
    public ResponseEntity<MetricsResult> metricsSeries(
            @PathVariable UUID wsId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "30") int minutes,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        WorkspaceEntity workspace = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "workspace not found: " + wsId));
        int win = Math.max(5, Math.min(minutes, 360));
        return ResponseEntity.ok(metricsQuery.query(workspace, metric, "last_" + win + "m"));
    }

    /** incident 상세 facade: 기본 정보·관련 이벤트·영향 pipeline id·리포트 목록. */
    @GetMapping("/incidents/{incidentId}/detail")
    public ResponseEntity<IncidentDetailResponse> incidentDetail(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        IncidentResponse incident = incidentService.get(wsId, incidentId);
        List<EventResponse> events = eventService.list(wsId, null, null, incidentId);
        List<IncidentReportResponse> reports = incidentReportService.list(incidentId);
        // (#595) AI 분석 리포트가 있으면 비어 있던 rca를 채운다(분석 run 비동기·콜백 없음).
        incident = incidentService.backfillRcaIfMissing(wsId, incidentId, incident, reports);
        return ResponseEntity.ok(new IncidentDetailResponse(
                incident,
                events,
                impactPipelineIds(incident, events),
                reports));
    }

    /** incident 리포트 목록 facade. */
    @GetMapping("/incidents/{incidentId}/reports")
    public ResponseEntity<List<IncidentReportResponse>> incidentReports(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        incidentService.get(wsId, incidentId);
        return ResponseEntity.ok(incidentReportService.list(incidentId));
    }

    /** #964 RCA 운영자 피드백 제출(채택/거부/수정). 축적되면 평가·캘리브레이션용 gold set 이 된다. */
    @PostMapping("/incidents/{incidentId}/rca-feedback")
    public ResponseEntity<RcaFeedbackResponse> submitRcaFeedback(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @RequestBody RcaFeedbackRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(rcaFeedbackService.submit(
                wsId, incidentId, request, principal == null ? null : principal.email()));
    }

    /** #964 인시던트별 RCA 피드백 목록(상세 화면에 현재 피드백 표시). */
    @GetMapping("/incidents/{incidentId}/rca-feedback")
    public ResponseEntity<List<RcaFeedbackResponse>> incidentRcaFeedback(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        incidentService.get(wsId, incidentId);
        return ResponseEntity.ok(rcaFeedbackService.list(wsId, incidentId));
    }

    /** #964 워크스페이스 RCA 피드백 전체 — gold set 조회(평가·캘리브레이션 연결 지점). */
    @GetMapping("/rca-feedback")
    public ResponseEntity<List<RcaFeedbackResponse>> workspaceRcaFeedback(
            @PathVariable UUID wsId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        return ResponseEntity.ok(rcaFeedbackService.listForWorkspace(wsId));
    }

    /** incident 리포트 본문 facade. */
    @GetMapping("/incidents/{incidentId}/reports/{reportId}")
    public ResponseEntity<IncidentReportResponse> incidentReport(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @PathVariable String reportId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        incidentService.get(wsId, incidentId);
        return ResponseEntity.ok(incidentReportService.get(incidentId, reportId));
    }

    private static List<UUID> impactPipelineIds(IncidentResponse incident, List<EventResponse> events) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if ("PIPELINE".equalsIgnoreCase(incident.sourceType()) && incident.sourceId() != null) {
            ids.add(incident.sourceId());
        }
        events.stream()
                .map(EventResponse::pipelineId)
                .filter(id -> id != null)
                .forEach(ids::add);
        return List.copyOf(ids);
    }
}
