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
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/settings")
public class WorkspaceSettingsController {

    private final WorkspaceSettingsService settingsService;

    public WorkspaceSettingsController(WorkspaceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/notifications")
    public NotificationSettingsResponse getNotifications(@PathVariable UUID wsId,
                                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return settingsService.getNotifications(wsId, principal);
    }

    @PutMapping("/notifications")
    public NotificationSettingsResponse updateNotifications(@PathVariable UUID wsId,
                                                            @AuthenticationPrincipal AuthenticatedUser principal,
                                                            @Valid @RequestBody NotificationSettingsRequest req) {
        requireAuth(principal);
        return settingsService.updateNotifications(wsId, principal, req);
    }

    @GetMapping("/thresholds")
    public ThresholdSettingsResponse getThresholds(@PathVariable UUID wsId,
                                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return settingsService.getThresholds(wsId, principal);
    }

    @PutMapping("/thresholds")
    public ThresholdSettingsResponse updateThresholds(@PathVariable UUID wsId,
                                                      @AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Valid @RequestBody ThresholdSettingsRequest req) {
        requireAuth(principal);
        return settingsService.updateThresholds(wsId, principal, req);
    }

    @GetMapping("/ai-policy")
    public AiPolicySettingsResponse getAiPolicy(@PathVariable UUID wsId,
                                                @AuthenticationPrincipal AuthenticatedUser principal) {
        requireAuth(principal);
        return settingsService.getAiPolicy(wsId, principal);
    }

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
