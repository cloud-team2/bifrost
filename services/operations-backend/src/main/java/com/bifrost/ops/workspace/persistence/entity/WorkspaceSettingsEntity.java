package com.bifrost.ops.workspace.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workspace_settings")
public class WorkspaceSettingsEntity {

    public static final boolean DEFAULT_SLACK_ENABLED = false;
    public static final String DEFAULT_SEVERITY = "warning";
    public static final long DEFAULT_LAG_WARNING = 5_000L;
    public static final long DEFAULT_LAG_CRITICAL = 20_000L;
    public static final boolean DEFAULT_AI_AUTONOMOUS = false;
    public static final int DEFAULT_AI_APPROVAL_WAIT_MINUTES = 10;
    public static final boolean DEFAULT_AI_PROD_LOCK = true;

    @Id
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "slack_enabled", nullable = false)
    private boolean slackEnabled = DEFAULT_SLACK_ENABLED;

    @Column(name = "slack_webhook_url", length = 500)
    private String slackWebhookUrl;

    @Column(name = "email_recipients", nullable = false)
    private String emailRecipients = "";

    @Column(name = "severity_policy", nullable = false, length = 20)
    private String severity = DEFAULT_SEVERITY;

    @Column(name = "lag_warning_threshold", nullable = false)
    private long lagWarningThreshold = DEFAULT_LAG_WARNING;

    @Column(name = "lag_critical_threshold", nullable = false)
    private long lagCriticalThreshold = DEFAULT_LAG_CRITICAL;

    @Column(name = "ai_autonomous", nullable = false)
    private boolean aiAutonomous = DEFAULT_AI_AUTONOMOUS;

    @Column(name = "ai_approval_wait_minutes", nullable = false)
    private int aiApprovalWaitMinutes = DEFAULT_AI_APPROVAL_WAIT_MINUTES;

    @Column(name = "ai_prod_lock", nullable = false)
    private boolean aiProdLock = DEFAULT_AI_PROD_LOCK;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static WorkspaceSettingsEntity defaults(UUID workspaceId) {
        WorkspaceSettingsEntity e = new WorkspaceSettingsEntity();
        e.setWorkspaceId(workspaceId);
        return e;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public List<String> emailRecipientList() {
        if (emailRecipients == null || emailRecipients.isBlank()) {
            return List.of();
        }
        return emailRecipients.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setEmailRecipientList(List<String> recipients) {
        this.emailRecipients = String.join("\n", recipients);
    }

    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public boolean isSlackEnabled() { return slackEnabled; }
    public void setSlackEnabled(boolean slackEnabled) { this.slackEnabled = slackEnabled; }
    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String slackWebhookUrl) { this.slackWebhookUrl = slackWebhookUrl; }
    public String getEmailRecipients() { return emailRecipients; }
    public void setEmailRecipients(String emailRecipients) { this.emailRecipients = emailRecipients; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public long getLagWarningThreshold() { return lagWarningThreshold; }
    public void setLagWarningThreshold(long lagWarningThreshold) { this.lagWarningThreshold = lagWarningThreshold; }
    public long getLagCriticalThreshold() { return lagCriticalThreshold; }
    public void setLagCriticalThreshold(long lagCriticalThreshold) { this.lagCriticalThreshold = lagCriticalThreshold; }
    public boolean isAiAutonomous() { return aiAutonomous; }
    public void setAiAutonomous(boolean aiAutonomous) { this.aiAutonomous = aiAutonomous; }
    public int getAiApprovalWaitMinutes() { return aiApprovalWaitMinutes; }
    public void setAiApprovalWaitMinutes(int aiApprovalWaitMinutes) { this.aiApprovalWaitMinutes = aiApprovalWaitMinutes; }
    public boolean isAiProdLock() { return aiProdLock; }
    public void setAiProdLock(boolean aiProdLock) { this.aiProdLock = aiProdLock; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
