package com.bifrost.ops.workspace.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.dto.AiPolicySettingsRequest;
import com.bifrost.ops.workspace.dto.AiPolicySettingsResponse;
import com.bifrost.ops.workspace.dto.NotificationSettingsResponse;
import com.bifrost.ops.workspace.dto.ThresholdSettingsResponse;
import com.bifrost.ops.workspace.service.WorkspaceSettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceSettingsControllerTest {

    private final WorkspaceSettingsService service = mock(WorkspaceSettingsService.class);
    private final WorkspaceSettingsController controller = new WorkspaceSettingsController(service);
    private final UUID wsId = UUID.randomUUID();
    private final AuthenticatedUser principal =
            new AuthenticatedUser(UUID.randomUUID(), wsId, "admin@bifrost.io");

    @Test
    void getNotificationsDelegates() {
        when(service.getNotifications(wsId, principal))
                .thenReturn(new NotificationSettingsResponse(true, "https://hooks.slack.com/services/T/B/C",
                        List.of("ops@bifrost.io"), "warning"));

        var out = controller.getNotifications(wsId, principal);

        assertThat(out.slackEnabled()).isTrue();
        assertThat(out.emailRecipients()).containsExactly("ops@bifrost.io");
    }

    @Test
    void getThresholdsDelegates() {
        when(service.getThresholds(wsId, principal)).thenReturn(new ThresholdSettingsResponse(5_000, 20_000));

        var out = controller.getThresholds(wsId, principal);

        assertThat(out.warning()).isEqualTo(5_000);
        assertThat(out.critical()).isEqualTo(20_000);
    }

    @Test
    void putAiPolicyDelegates() {
        AiPolicySettingsRequest req = new AiPolicySettingsRequest(true, 10, true);
        when(service.updateAiPolicy(wsId, principal, req)).thenReturn(new AiPolicySettingsResponse(true, 10, true));

        var out = controller.updateAiPolicy(wsId, principal, req);

        assertThat(out.autonomous()).isTrue();
    }

    @Test
    void rejectsUnauthenticated() {
        assertThatThrownBy(() -> controller.getAiPolicy(wsId, null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
