-- S3: 거버넌스 게이트 테이블 (evidence_ref / approval / change_ticket / idempotency_key)
-- + audit_events 컬럼 보강

-- 승인 토큰: AI agent mutation에 대한 사람 승인 또는 자동 승인 기록
CREATE TABLE approval (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    actor        VARCHAR(255) NOT NULL,
    operation    VARCHAR(100) NOT NULL,
    params_hash  VARCHAR(64) NOT NULL,
    decision     VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_tenant ON approval(tenant_id, created_at DESC);

-- mutation 전후 스냅샷 (RCA 증거)
CREATE TABLE evidence_ref (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    mutation_id  UUID,
    stage        VARCHAR(10) NOT NULL,  -- BEFORE / AFTER
    snapshot     JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_evidence_mutation ON evidence_ref(mutation_id);

-- 변경 티켓
CREATE TABLE change_ticket (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    title      VARCHAR(255) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'OPEN',  -- OPEN / CLOSED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_change_ticket_tenant ON change_ticket(tenant_id, created_at DESC);

-- 멱등성 키: 동일 요청 중복 실행 방지
CREATE TABLE idempotency_key (
    id         UUID PRIMARY KEY,
    idem_key   VARCHAR(128) NOT NULL,
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    status     VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',  -- PROCESSING / DONE
    result     JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_idempotency_key_tenant ON idempotency_key(idem_key, tenant_id);

-- audit_events 거버넌스 컬럼 보강
ALTER TABLE audit_events ADD COLUMN policy_decision VARCHAR(30);
ALTER TABLE audit_events ADD COLUMN approval_id     UUID REFERENCES approval(id);
ALTER TABLE audit_events ADD COLUMN evidence_id     UUID REFERENCES evidence_ref(id);
