-- #964 RCA 운영자 피드백(채택/거부/수정). 축적되면 RCA 평가(AC@k)·confidence 캘리브레이션(ECE)용 gold set 이 된다.
CREATE TABLE rca_feedback (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    incident_id             UUID NOT NULL REFERENCES incidents(id),
    run_id                  VARCHAR(255),
    rca_root_cause_id       VARCHAR(255),
    rca_confidence          DOUBLE PRECISION,
    verdict                 VARCHAR(20) NOT NULL, -- ACCEPTED / REJECTED / CORRECTED
    corrected_root_cause_id VARCHAR(255),
    trigger_label           TEXT,
    symptom_label           TEXT,
    operator                VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 인시던트별 최신 피드백 조회(상세 화면) + 워크스페이스 gold set 조회(평가)
CREATE INDEX idx_rca_feedback_tenant_incident ON rca_feedback(tenant_id, incident_id, created_at DESC);
CREATE INDEX idx_rca_feedback_tenant_created ON rca_feedback(tenant_id, created_at DESC);
