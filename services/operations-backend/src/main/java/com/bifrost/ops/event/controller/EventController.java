package com.bifrost.ops.event.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.event.dto.EventResponse;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 이벤트 로그 API(#70, FR-019). {@code GET /api/v1/workspaces/{wsId}/events?level=&pipelineId=&incidentId=}.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/events")
public class EventController {

    private final EventService eventService;
    private final WorkspaceAccessGuard accessGuard;

    public EventController(EventService eventService, WorkspaceAccessGuard accessGuard) {
        this.eventService = eventService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public List<EventResponse> list(@PathVariable UUID wsId,
                                    @AuthenticationPrincipal AuthenticatedUser principal,
                                    @RequestParam(required = false) String level,
                                    @RequestParam(required = false) UUID pipelineId,
                                    @RequestParam(required = false) UUID incidentId) {
        accessGuard.requireAccess(wsId, principal);
        return eventService.list(wsId, parseLevel(level), pipelineId, incidentId);
    }

    private static EventLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        try {
            return EventLevel.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 level: " + level);
        }
    }
}
