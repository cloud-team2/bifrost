package com.bifrost.ops.streaming;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 워크스페이스 SSE 스트림(#70). {@code GET /api/v1/workspaces/{wsId}/events/stream}.
 *
 * <p>브라우저 {@code EventSource}는 Authorization 헤더를 붙일 수 없으므로 토큰을
 * {@code ?access_token=} 쿼리로 전달한다({@code JwtAuthenticationFilter}가 헤더 부재 시 쿼리에서 읽음).
 * 전송 이벤트: {@code pipeline_status_changed}, {@code connector_state_changed}.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/events")
public class SseController {

    private final SsePublisher ssePublisher;
    private final WorkspaceAccessGuard accessGuard;

    public SseController(SsePublisher ssePublisher, WorkspaceAccessGuard accessGuard) {
        this.ssePublisher = ssePublisher;
        this.accessGuard = accessGuard;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID wsId,
                             @AuthenticationPrincipal AuthenticatedUser principal,
                             HttpServletResponse response) {
        accessGuard.requireAccess(wsId, principal);
        // nginx ingress가 SSE 응답을 버퍼링하지 않도록(HTTP/2 스트림 끊김 방지, #630).
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return ssePublisher.subscribe(wsId);
    }
}
