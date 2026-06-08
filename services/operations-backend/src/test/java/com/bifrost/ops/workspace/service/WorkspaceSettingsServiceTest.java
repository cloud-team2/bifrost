package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.dto.AiPolicySettingsRequest;
import com.bifrost.ops.workspace.dto.NotificationSettingsRequest;
import com.bifrost.ops.workspace.dto.ThresholdSettingsRequest;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceSettingsServiceTest {

    @Mock private WorkspaceSettingsRepository settingsRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ProjectMemberRepository memberRepository;
    @Mock private WorkspaceAccessGuard accessGuard;

    private final UUID wsId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(userId, wsId, "owner@bifrost.io");

    private WorkspaceSettingsService service() {
        return new WorkspaceSettingsService(settingsRepository, workspaceRepository, memberRepository, accessGuard);
    }

    @Test
    void getNotificationsReturnsDefaultsWhenSettingsRowMissing() {
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(settingsRepository.findById(wsId)).thenReturn(Optional.empty());

        var out = service().getNotifications(wsId, principal);

        assertThat(out.slackEnabled()).isFalse();
        assertThat(out.slackWebhookUrl()).isNull();
        assertThat(out.emailRecipients()).isEmpty();
        assertThat(out.severity()).isEqualTo("warning");
        verify(accessGuard).requireAccess(wsId, principal);
    }

    @Test
    void updateNotificationsNormalizesAndPersistsManagerSettings() {
        WorkspaceSettingsEntity settings = WorkspaceSettingsEntity.defaults(wsId);
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(wsId, userId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(settingsRepository.findById(wsId)).thenReturn(Optional.of(settings));
        when(settingsRepository.saveAndFlush(settings)).thenReturn(settings);

        var out = service().updateNotifications(wsId, principal, new NotificationSettingsRequest(
                true,
                " https://hooks.slack.com/services/T/B/C ",
                List.of("Ops@BIFROST.io", "ops@bifrost.io", "dev@bifrost.io"),
                "ERROR"));

        assertThat(out.slackEnabled()).isTrue();
        assertThat(out.slackWebhookUrl()).isEqualTo("https://hooks.slack.com/services/T/B/C");
        assertThat(out.emailRecipients()).containsExactly("ops@bifrost.io", "dev@bifrost.io");
        assertThat(out.severity()).isEqualTo("error");
    }

    @Test
    void updateNotificationsRejectsInvalidSlackUrl() {
        allowManager();
        when(settingsRepository.findById(wsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateNotifications(wsId, principal, new NotificationSettingsRequest(
                true, "https://example.com/services/T/B/C", List.of(), "warning")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateNotificationsRejectsInvalidEmail() {
        allowManager();
        when(settingsRepository.findById(wsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateNotifications(wsId, principal, new NotificationSettingsRequest(
                false, null, List.of("not-email"), "warning")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateThresholdsRequiresCriticalGreaterThanWarning() {
        allowManager();
        when(settingsRepository.findById(wsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateThresholds(wsId, principal, new ThresholdSettingsRequest(100L, 100L)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateThresholdsPersistsPositiveValues() {
        WorkspaceSettingsEntity settings = WorkspaceSettingsEntity.defaults(wsId);
        allowManager();
        when(settingsRepository.findById(wsId)).thenReturn(Optional.of(settings));
        when(settingsRepository.saveAndFlush(settings)).thenReturn(settings);

        var out = service().updateThresholds(wsId, principal, new ThresholdSettingsRequest(10_000L, 50_000L));

        assertThat(out.warning()).isEqualTo(10_000L);
        assertThat(out.critical()).isEqualTo(50_000L);
    }

    @Test
    void updateAiPolicyRequiresPositiveApprovalWait() {
        allowManager();
        when(settingsRepository.findById(wsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateAiPolicy(wsId, principal, new AiPolicySettingsRequest(true, 0, true)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateAiPolicyRejectsNonManager() {
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(wsId, userId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(false);
        when(workspaceRepository.existsByIdAndOwnerUserId(wsId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service().updateAiPolicy(wsId, principal, new AiPolicySettingsRequest(true, 10, false)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
    }

    @Test
    void updateAiPolicyPersistsManagerSettings() {
        WorkspaceSettingsEntity settings = WorkspaceSettingsEntity.defaults(wsId);
        allowManager();
        when(settingsRepository.findById(wsId)).thenReturn(Optional.of(settings));
        when(settingsRepository.saveAndFlush(settings)).thenReturn(settings);

        var out = service().updateAiPolicy(wsId, principal, new AiPolicySettingsRequest(true, 30, false));

        assertThat(out.autonomous()).isTrue();
        assertThat(out.approvalWaitMinutes()).isEqualTo(30);
        assertThat(out.prodLock()).isFalse();
    }

    private void allowManager() {
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(wsId, userId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
    }
}
