package com.bifrost.ops.workspace.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record NotificationSettingsRequest(
        Boolean slackEnabled,
        @JsonAlias("slackWebhook") String slackWebhookUrl,
        List<String> emailRecipients,
        @JsonAlias("severityPolicy") String severity
) {
}
