package com.bifrost.ops.workspace.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 워크스페이스 플랫폼 API(frontend-facing, FR-002). 소유 기반 다중 워크스페이스(#72).
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /** 로그인 사용자가 소유한 워크스페이스 목록. */
    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return workspaceService.list(principal);
    }

    /** 워크스페이스 생성(이름 → projectKey 슬러그 자동 + KafkaUser/ACL 프로비저닝 트리거). */
    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @Valid @RequestBody WorkspaceCreateRequest req) {
        requireAuth(principal);
        return ResponseEntity.status(201).body(workspaceService.create(principal, req));
    }

    /** 워크스페이스 상세(소유/접근 검증). */
    @GetMapping("/{wsId}")
    public WorkspaceResponse get(@PathVariable UUID wsId,
                                 @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return workspaceService.get(wsId, principal);
    }

    private static void requireAuth(AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
    }
}
