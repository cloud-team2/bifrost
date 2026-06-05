package com.bifrost.ops.pipeline.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.service.PipelineService;
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

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
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

    /** 삭제(FR-005). connector CR 삭제 + 행 제거. creating 중에는 불가. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID wsId,
                                       @PathVariable UUID id,
                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        pipelineService.delete(wsId, principal, id);
        return ResponseEntity.noContent().build();
    }
}
