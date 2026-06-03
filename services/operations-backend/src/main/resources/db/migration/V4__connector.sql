-- V4__connector.sql — connector 메타데이터 테이블 (설계 §4 Data Model 3.5)
--
-- #11에서 V3__connector.sql로 추가했으나 기존 스캐폴드 V3__create_discovered_services.sql과
-- Flyway 버전이 충돌해 V4로 이동한다(#43). 현재 스캐폴드 스키마(tenants/datasources/pipelines)
-- 기준이므로 connector.pipeline_id → pipelines(id) FK를 직접 건다(V2가 이미 교차 FK를 쓰는 것과 일관).
--
-- kind/state는 JPA enum(ConnectorKind/ConnectorRuntimeState)과 맞춰 대문자로 저장한다.

CREATE TABLE connectors (
    id               UUID PRIMARY KEY,
    pipeline_id      UUID NOT NULL REFERENCES pipelines(id),
    cr_name          VARCHAR(255) NOT NULL,
    kind             VARCHAR(20)  NOT NULL,          -- SOURCE / SINK
    connector_class  VARCHAR(255) NOT NULL,          -- Debezium / JDBC Sink class
    -- RUNNING / PARTIALLY_FAILED / FAILED / PAUSED / UNASSIGNED / UNKNOWN (부록 B.2, watcher 갱신)
    state            VARCHAR(30),
    tasks_max        INT NOT NULL,                   -- source=1, sink=3
    last_error       TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP,                      -- watch 갱신 시각
    CONSTRAINT connectors_kind_chk CHECK (kind IN ('SOURCE', 'SINK')),
    CONSTRAINT connectors_cr_name_uq UNIQUE (cr_name)
);

-- pipeline별 connector 조회(EDA 1개 / CDC 2개) 최적화
CREATE INDEX idx_connectors_pipeline ON connectors(pipeline_id);
-- 상태 필터링(예: FAILED connector 집계)용
CREATE INDEX idx_connectors_state ON connectors(state);
