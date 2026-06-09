package com.bifrost.ops.workspace.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.ProjectMemberAddRequest;
import com.bifrost.ops.workspace.dto.ProjectMemberResponse;
import com.bifrost.ops.workspace.dto.ProjectMemberUpdateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.dto.WorkspaceUpdateRequest;
import com.bifrost.ops.workspace.service.ProjectMemberService;
import com.bifrost.ops.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@Tag(name = "Workspace", description = "워크스페이스 CRUD와 멤버 관리 API")
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final ProjectMemberService memberService;

    public WorkspaceController(WorkspaceService workspaceService,
                               ProjectMemberService memberService) {
        this.workspaceService = workspaceService;
        this.memberService = memberService;
    }

    /** 로그인 사용자가 소유한 워크스페이스 목록. */
    @Operation(summary = "워크스페이스 목록", description = "현재 사용자가 소속된 워크스페이스 목록과 파이프라인 카운트를 반환한다.")
    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return workspaceService.list(principal);
    }

    /** 워크스페이스 생성(이름 → projectKey 슬러그 자동 + KafkaUser/ACL 프로비저닝 트리거). */
    @Operation(summary = "워크스페이스 생성", description = "워크스페이스를 생성하고 생성자를 OWNER 멤버로 등록한다.")
    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @Valid @RequestBody WorkspaceCreateRequest req) {
        requireAuth(principal);
        return ResponseEntity.status(201).body(workspaceService.create(principal, req));
    }

    /** 워크스페이스 상세(소유/접근 검증). */
    @Operation(summary = "워크스페이스 상세", description = "워크스페이스 접근 권한을 검증한 뒤 상세 정보를 반환한다.")
    @GetMapping("/{wsId}")
    public WorkspaceResponse get(@PathVariable UUID wsId,
                                 @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return workspaceService.get(wsId, principal);
    }

    @Operation(summary = "워크스페이스 수정", description = "OWNER/ADMIN이 워크스페이스 이름과 timezone을 수정한다.")
    @PatchMapping("/{wsId}")
    public WorkspaceResponse update(@PathVariable UUID wsId,
                                    @AuthenticationPrincipal AuthenticatedUser principal,
                                    @Valid @RequestBody WorkspaceUpdateRequest req) {
        requireAuth(principal);
        return workspaceService.update(wsId, principal, req);
    }

    @Operation(summary = "멤버 목록", description = "워크스페이스 멤버가 멤버 목록을 조회한다.")
    @GetMapping("/{wsId}/members")
    public List<ProjectMemberResponse> listMembers(@PathVariable UUID wsId,
                                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return memberService.list(wsId, principal);
    }

    @Operation(summary = "멤버 추가", description = "OWNER/ADMIN이 이메일로 사용자를 워크스페이스 멤버로 추가한다.")
    @PostMapping("/{wsId}/members")
    public ResponseEntity<ProjectMemberResponse> addMember(@PathVariable UUID wsId,
                                                           @AuthenticationPrincipal AuthenticatedUser principal,
                                                           @Valid @RequestBody ProjectMemberAddRequest req) {
        requireAuth(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.add(wsId, principal, req));
    }

    @Operation(summary = "멤버 역할 변경", description = "OWNER/ADMIN이 OWNER가 아닌 멤버의 역할을 변경한다.")
    @PatchMapping("/{wsId}/members/{userId}")
    public ProjectMemberResponse updateMember(@PathVariable UUID wsId,
                                              @PathVariable UUID userId,
                                              @AuthenticationPrincipal AuthenticatedUser principal,
                                              @Valid @RequestBody ProjectMemberUpdateRequest req) {
        requireAuth(principal);
        return memberService.update(wsId, userId, principal, req);
    }

    @Operation(summary = "멤버 제거", description = "OWNER/ADMIN이 OWNER가 아닌 멤버를 워크스페이스에서 제거한다.")
    @DeleteMapping("/{wsId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID wsId,
                                             @PathVariable UUID userId,
                                             @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        memberService.remove(wsId, userId, principal);
        return ResponseEntity.noContent().build();
    }

    private static void requireAuth(AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
    }
}
