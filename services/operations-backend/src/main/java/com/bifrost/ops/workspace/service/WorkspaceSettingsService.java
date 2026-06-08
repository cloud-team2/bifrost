package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.dto.AiPolicySettingsRequest;
import com.bifrost.ops.workspace.dto.AiPolicySettingsResponse;
import com.bifrost.ops.workspace.dto.NotificationSettingsRequest;
import com.bifrost.ops.workspace.dto.NotificationSettingsResponse;
import com.bifrost.ops.workspace.dto.ThresholdSettingsRequest;
import com.bifrost.ops.workspace.dto.ThresholdSettingsResponse;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WorkspaceSettingsService {

    private static final Set<String> SEVERITIES = Set.of("all", "warning", "error");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final WorkspaceSettingsRepository settingsRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectMemberRepository memberRepository;
    private final WorkspaceAccessGuard accessGuard;

    public WorkspaceSettingsService(WorkspaceSettingsRepository settingsRepository,
                                    WorkspaceRepository workspaceRepository,
                                    ProjectMemberRepository memberRepository,
                                    WorkspaceAccessGuard accessGuard) {
        this.settingsRepository = settingsRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsResponse getNotifications(UUID wsId, AuthenticatedUser principal) {
        requireWorkspaceAccess(wsId, principal);
        return NotificationSettingsResponse.from(findOrDefault(wsId));
    }

    @Transactional
    public NotificationSettingsResponse updateNotifications(UUID wsId,
                                                            AuthenticatedUser principal,
                                                            NotificationSettingsRequest req) {
        requireManager(wsId, principal);
        WorkspaceSettingsEntity settings = findOrDefault(wsId);
        boolean slackEnabled = req.slackEnabled() != null ? req.slackEnabled() : settings.isSlackEnabled();
        String slackWebhook = normalizeNullable(req.slackWebhookUrl());
        validateSlack(slackEnabled, slackWebhook);
        List<String> recipients = req.emailRecipients() != null
                ? normalizeEmails(req.emailRecipients())
                : settings.emailRecipientList();
        String severity = req.severity() != null ? req.severity().trim().toLowerCase() : settings.getSeverity();
        validateSeverity(severity);

        settings.setSlackEnabled(slackEnabled);
        settings.setSlackWebhookUrl(slackWebhook);
        settings.setEmailRecipientList(recipients);
        settings.setSeverity(severity);
        return NotificationSettingsResponse.from(settingsRepository.saveAndFlush(settings));
    }

    @Transactional(readOnly = true)
    public ThresholdSettingsResponse getThresholds(UUID wsId, AuthenticatedUser principal) {
        requireWorkspaceAccess(wsId, principal);
        return ThresholdSettingsResponse.from(findOrDefault(wsId));
    }

    @Transactional
    public ThresholdSettingsResponse updateThresholds(UUID wsId,
                                                      AuthenticatedUser principal,
                                                      ThresholdSettingsRequest req) {
        requireManager(wsId, principal);
        WorkspaceSettingsEntity settings = findOrDefault(wsId);
        long warning = req.warning() != null ? req.warning() : settings.getLagWarningThreshold();
        long critical = req.critical() != null ? req.critical() : settings.getLagCriticalThreshold();
        validateThresholds(warning, critical);

        settings.setLagWarningThreshold(warning);
        settings.setLagCriticalThreshold(critical);
        return ThresholdSettingsResponse.from(settingsRepository.saveAndFlush(settings));
    }

    @Transactional(readOnly = true)
    public AiPolicySettingsResponse getAiPolicy(UUID wsId, AuthenticatedUser principal) {
        requireWorkspaceAccess(wsId, principal);
        return AiPolicySettingsResponse.from(findOrDefault(wsId));
    }

    @Transactional
    public AiPolicySettingsResponse updateAiPolicy(UUID wsId,
                                                   AuthenticatedUser principal,
                                                   AiPolicySettingsRequest req) {
        requireManager(wsId, principal);
        WorkspaceSettingsEntity settings = findOrDefault(wsId);
        boolean autonomous = req.autonomous() != null ? req.autonomous() : settings.isAiAutonomous();
        int approvalWaitMinutes = req.approvalWaitMinutes() != null
                ? req.approvalWaitMinutes()
                : settings.getAiApprovalWaitMinutes();
        if (approvalWaitMinutes <= 0) {
            throw validation("approvalWaitMinutes는 0보다 커야 합니다");
        }

        settings.setAiAutonomous(autonomous);
        settings.setAiApprovalWaitMinutes(approvalWaitMinutes);
        settings.setAiProdLock(req.prodLock() != null ? req.prodLock() : settings.isAiProdLock());
        return AiPolicySettingsResponse.from(settingsRepository.saveAndFlush(settings));
    }

    private WorkspaceSettingsEntity findOrDefault(UUID wsId) {
        return settingsRepository.findById(wsId)
                .orElseGet(() -> WorkspaceSettingsEntity.defaults(wsId));
    }

    private void requireWorkspaceAccess(UUID wsId, AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        if (!workspaceRepository.existsById(wsId)) {
            throw new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다");
        }
    }

    private void requireManager(UUID wsId, AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        if (!workspaceRepository.existsById(wsId)) {
            throw new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다");
        }
        boolean hasRole = memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(
                wsId, principal.userId(), List.of(Role.OWNER, Role.ADMIN));
        if (hasRole || workspaceRepository.existsByIdAndOwnerUserId(wsId, principal.userId())) {
            return;
        }
        throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, "워크스페이스 관리 권한이 없습니다");
    }

    private static void validateSlack(boolean slackEnabled, String slackWebhook) {
        if (!slackEnabled && (slackWebhook == null || slackWebhook.isBlank())) {
            return;
        }
        if (slackWebhook == null || slackWebhook.isBlank()) {
            throw validation("Slack webhook URL이 필요합니다");
        }
        try {
            URI uri = URI.create(slackWebhook);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !uri.getHost().equalsIgnoreCase("hooks.slack.com")
                    || !uri.getPath().startsWith("/services/")) {
                throw validation("Slack webhook URL 형식이 올바르지 않습니다");
            }
        } catch (IllegalArgumentException e) {
            throw validation("Slack webhook URL 형식이 올바르지 않습니다");
        }
    }

    private static List<String> normalizeEmails(List<String> emails) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String email : emails) {
            String value = normalizeNullable(email);
            if (value == null) {
                continue;
            }
            if (!EMAIL.matcher(value).matches()) {
                throw validation("이메일 형식이 올바르지 않습니다: " + value);
            }
            normalized.add(value.toLowerCase());
        }
        return List.copyOf(normalized);
    }

    private static void validateSeverity(String severity) {
        if (!SEVERITIES.contains(severity)) {
            throw validation("지원하지 않는 severity 정책입니다");
        }
    }

    private static void validateThresholds(long warning, long critical) {
        if (warning <= 0 || critical <= 0) {
            throw validation("임계값은 0보다 커야 합니다");
        }
        if (critical <= warning) {
            throw validation("critical 임계값은 warning보다 커야 합니다");
        }
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
