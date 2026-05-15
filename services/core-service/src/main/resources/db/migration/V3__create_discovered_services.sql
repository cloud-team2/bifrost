CREATE TABLE discovered_services (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    consumer_group_id   VARCHAR(255) NOT NULL,
    subscribed_topics   TEXT[] NOT NULL,
    display_name        VARCHAR(100),
    description         TEXT,
    current_lag         BIGINT,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    first_seen_at       TIMESTAMP NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, consumer_group_id)
);

CREATE INDEX idx_discovered_services_tenant ON discovered_services(tenant_id);
