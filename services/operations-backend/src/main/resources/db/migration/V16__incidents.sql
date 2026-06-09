-- S2: incidents 테이블 + events 컬럼 보강

CREATE TABLE incidents (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    grouping_key VARCHAR(255) NOT NULL,
    severity     VARCHAR(10) NOT NULL,   -- WARN / ERROR / CRITICAL
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',  -- OPEN / RESOLVED
    title        TEXT NOT NULL,
    rca          TEXT,
    source_type  VARCHAR(50),
    source_id    UUID,
    opened_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_incidents_tenant_status ON incidents(tenant_id, status, created_at DESC);
CREATE INDEX idx_incidents_grouping_key  ON incidents(tenant_id, grouping_key);

-- events 테이블 거버넌스 연결 컬럼 보강
ALTER TABLE events ADD COLUMN incident_id UUID REFERENCES incidents(id);
ALTER TABLE events ADD COLUMN category    VARCHAR(50);
