package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;

import java.util.List;

public record NotificationSettingsResponse(
        boolean slackEnabled,
        String slackWebhookUrl,
        List<String> emailRecipients,
        String severity
) {
    public static NotificationSettingsResponse from(WorkspaceSettingsEntity settings) {
        return new NotificationSettingsResponse(
                settings.isSlackEnabled(),
                settings.getSlackWebhookUrl(),
                settings.emailRecipientList(),
                settings.getSeverity());
    }
}
