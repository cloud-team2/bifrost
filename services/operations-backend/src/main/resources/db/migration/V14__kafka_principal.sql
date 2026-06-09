CREATE TABLE kafka_principal (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    username VARCHAR(255) NOT NULL,
    secret_ref VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','REVOKED')),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    deactivated_at TIMESTAMP,
    revoked_at TIMESTAMP,
    UNIQUE (workspace_id, username)
);

CREATE INDEX idx_kafka_principal_workspace ON kafka_principal(workspace_id);
