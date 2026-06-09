CREATE TABLE workspace_settings (
    workspace_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,

    slack_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    slack_webhook_url VARCHAR(500),
    email_recipients TEXT NOT NULL DEFAULT '',
    severity_policy VARCHAR(20) NOT NULL DEFAULT 'warning',

    lag_warning_threshold BIGINT NOT NULL DEFAULT 5000,
    lag_critical_threshold BIGINT NOT NULL DEFAULT 20000,

    ai_autonomous BOOLEAN NOT NULL DEFAULT FALSE,
    ai_approval_wait_minutes INTEGER NOT NULL DEFAULT 10,
    ai_prod_lock BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT workspace_settings_severity_chk
        CHECK (severity_policy IN ('all', 'warning', 'error')),
    CONSTRAINT workspace_settings_lag_threshold_chk
        CHECK (lag_warning_threshold > 0 AND lag_critical_threshold > lag_warning_threshold),
    CONSTRAINT workspace_settings_ai_wait_chk
        CHECK (ai_approval_wait_minutes > 0)
);
