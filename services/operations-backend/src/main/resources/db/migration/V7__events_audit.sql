-- 이벤트/감사 최소 구현(#70). append-only. 교차 FK는 걸지 않고 uuid 컬럼만 둔다(todo.md 규칙).
CREATE TABLE events (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    pipeline_id  UUID,                       -- 파이프라인 무관 이벤트는 NULL
    level        VARCHAR(10) NOT NULL,       -- INFO / WARN / ERROR
    type         VARCHAR(50) NOT NULL,       -- PIPELINE_CREATED / PIPELINE_STATUS_CHANGED / ...
    message      TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_tenant_created ON events(tenant_id, created_at DESC);
CREATE INDEX idx_events_pipeline ON events(pipeline_id);

CREATE TABLE audit_events (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    actor        VARCHAR(255),               -- 사용자 이메일 또는 'system'
    action       VARCHAR(100) NOT NULL,      -- PIPELINE_CREATE / PIPELINE_STATUS_TRANSITION / ...
    target_type  VARCHAR(50),                -- PIPELINE 등
    target_id    UUID,
    detail       TEXT,                        -- 비밀값 미포함
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_created ON audit_events(tenant_id, created_at DESC);
