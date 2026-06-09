package com.bifrost.ops.monitoring.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
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
import java.util.Map;
import java.util.UUID;

/**
 * 모니터링 read API — overview·resource-events·incidents(S5).
 * incidents 엔드포인트는 S2(#258) merge 후 IncidentService로 교체 예정.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/monitoring")
public class MonitoringController {

    private final MonitoringReadService monitoringReadService;
    private final WorkspaceAccessGuard accessGuard;

    public MonitoringController(MonitoringReadService monitoringReadService,
                                WorkspaceAccessGuard accessGuard) {
        this.monitoringReadService = monitoringReadService;
        this.accessGuard = accessGuard;
    }

    /** 워크스페이스 전체 health 집계. */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview(
            @PathVariable UUID wsId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireMember(wsId, principal);
        return ResponseEntity.ok(monitoringReadService.overview(wsId));
    }

    /** KRaft rebalance·leader election 이벤트 목록. */
    @GetMapping("/resource-events")
    public ResponseEntity<List<ResourceEventResponse>> resourceEvents(
            @PathVariable UUID wsId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireMember(wsId, principal);
        return ResponseEntity.ok(monitoringReadService.resourceEvents(wsId));
    }

    /**
     * incident 목록 stub — S2(#258) merge 후 IncidentService 연결 예정.
     * response shape 계약용으로 먼저 노출.
     */
    @GetMapping("/incidents")
    public ResponseEntity<List<Map<String, Object>>> incidents(
            @PathVariable UUID wsId,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireMember(wsId, principal);
        return ResponseEntity.ok(List.of());
    }

    /**
     * incident 상세 stub — S2(#258) merge 후 IncidentService 연결 예정.
     */
    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<Map<String, Object>> incident(
            @PathVariable UUID wsId,
            @PathVariable UUID incidentId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accessGuard.requireMember(wsId, principal);
        return ResponseEntity.ok(Map.of(
                "id", incidentId,
                "status", "stub — pending S2 merge",
                "wsId", wsId));
    }
}
