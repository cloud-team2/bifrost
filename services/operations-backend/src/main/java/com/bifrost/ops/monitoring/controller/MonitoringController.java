package com.bifrost.ops.monitoring.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.event.dto.EventResponse;
import com.bifrost.ops.incident.IncidentReportService;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.incident.dto.IncidentDetailResponse;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.monitoring.dto.ResourceEventResponse;
import com.bifrost.ops.monitoring.service.MonitoringReadService;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public MonitoringController(MonitoringReadService monitoringReadService,
                                IncidentService incidentService,
                                EventService eventService,
                                IncidentReportService incidentReportService,
                                WorkspaceAccessGuard accessGuard) {
        this.monitoringReadService = monitoringReadService;
        this.incidentService = incidentService;
        this.eventService = eventService;
        this.incidentReportService = incidentReportService;
        this.accessGuard = accessGuard;
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
