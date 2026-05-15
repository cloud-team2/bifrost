CREATE TABLE datasources (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    name                    VARCHAR(100) NOT NULL,
    db_type                 VARCHAR(20) NOT NULL,
    host                    VARCHAR(255) NOT NULL,
    port                    INT NOT NULL,
    db_name                 VARCHAR(255) NOT NULL,
    username                VARCHAR(255) NOT NULL,
    secret_ref              VARCHAR(255) NOT NULL,
    cdc_readiness_status    VARCHAR(20),
    cdc_readiness_report    JSONB,
    last_inspected_at       TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_datasources_tenant ON datasources(tenant_id);

CREATE TABLE pipelines (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    name                    VARCHAR(100) NOT NULL,
    type                    VARCHAR(20) NOT NULL DEFAULT 'CDC',
    source_datasource_id    UUID NOT NULL REFERENCES datasources(id),
    tables                  JSONB NOT NULL,
    source_connector_name   VARCHAR(255),
    topic_name              VARCHAR(255),
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    status_message          TEXT,
    status_updated_at       TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_pipelines_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipelines_status ON pipelines(status);
