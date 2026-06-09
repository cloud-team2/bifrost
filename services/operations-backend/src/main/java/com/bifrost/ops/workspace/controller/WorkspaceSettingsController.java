package com.bifrost.ops.workspace.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.dto.AiPolicySettingsRequest;
import com.bifrost.ops.workspace.dto.AiPolicySettingsResponse;
import com.bifrost.ops.workspace.dto.NotificationSettingsRequest;
import com.bifrost.ops.workspace.dto.NotificationSettingsResponse;
import com.bifrost.ops.workspace.dto.ThresholdSettingsRequest;
import com.bifrost.ops.workspace.dto.ThresholdSettingsResponse;
import com.bifrost.ops.workspace.service.WorkspaceSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Workspace Settings", description = "알림, 임계값, AI 자동복구 정책 API")
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/settings")
public class WorkspaceSettingsController {

    private final WorkspaceSettingsService settingsService;

    public WorkspaceSettingsController(WorkspaceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Operation(summary = "알림 설정 조회", description = "Slack webhook, email recipients, severity 정책을 조회한다.")
    @GetMapping("/notifications")
    public NotificationSettingsResponse getNotifications(@PathVariable UUID wsId,
                                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return settingsService.getNotifications(wsId, principal);
    }

    @Operation(summary = "알림 설정 수정", description = "OWNER/ADMIN이 Slack/email 알림 설정을 수정한다.")
    @PutMapping("/notifications")
    public NotificationSettingsResponse updateNotifications(@PathVariable UUID wsId,
                                                            @AuthenticationPrincipal AuthenticatedUser principal,
                                                            @Valid @RequestBody NotificationSettingsRequest req) {
        requireAuth(principal);
        return settingsService.updateNotifications(wsId, principal, req);
    }

    @Operation(summary = "임계값 설정 조회", description = "consumer lag warning/critical 임계값을 조회한다.")
    @GetMapping("/thresholds")
    public ThresholdSettingsResponse getThresholds(@PathVariable UUID wsId,
                                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return settingsService.getThresholds(wsId, principal);
    }

    @Operation(summary = "임계값 설정 수정", description = "OWNER/ADMIN이 warning/critical 임계값을 수정한다.")
    @PutMapping("/thresholds")
    public ThresholdSettingsResponse updateThresholds(@PathVariable UUID wsId,
                                                      @AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Valid @RequestBody ThresholdSettingsRequest req) {
        requireAuth(principal);
        return settingsService.updateThresholds(wsId, principal, req);
    }

    @Operation(summary = "AI 정책 조회", description = "AI 자동복구, 승인 대기 시간, prod lock 정책을 조회한다.")
    @GetMapping("/ai-policy")
    public AiPolicySettingsResponse getAiPolicy(@PathVariable UUID wsId,
                                                @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return settingsService.getAiPolicy(wsId, principal);
    }

    @Operation(summary = "AI 정책 수정", description = "OWNER/ADMIN이 AI 자동복구 정책을 수정한다.")
    @PutMapping("/ai-policy")
    public AiPolicySettingsResponse updateAiPolicy(@PathVariable UUID wsId,
                                                   @AuthenticationPrincipal AuthenticatedUser principal,
                                                   @Valid @RequestBody AiPolicySettingsRequest req) {
        requireAuth(principal);
        return settingsService.updateAiPolicy(wsId, principal, req);
    }

    private static void requireAuth(AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
    }
}
