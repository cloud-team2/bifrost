package com.bifrost.ops.monitoring.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.monitoring.dto.ResourceEventResponse;
import com.bifrost.ops.monitoring.service.MonitoringReadService;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 모니터링 read API — overview·resource-events·incidents(S5).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/monitoring")
public class MonitoringController {

    private final MonitoringReadService monitoringReadService;
    private final IncidentService incidentService;
    private final WorkspaceAccessGuard accessGuard;

    public MonitoringController(MonitoringReadService monitoringReadService,
                                IncidentService incidentService,
                                WorkspaceAccessGuard accessGuard) {
        this.monitoringReadService = monitoringReadService;
        this.incidentService = incidentService;
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
}
