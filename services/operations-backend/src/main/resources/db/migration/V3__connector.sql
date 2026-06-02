-- V3__connector.sql — connector 메타데이터 테이블 (설계 §4 Data Model 3.5)
--
-- Flyway 교차 FK 규칙: 이 파일은 connector 테이블만 만들고, connector→pipeline FK는
-- 선언하지 않는다(pipeline_id는 uuid 컬럼으로만 둔다). 교차 FK는 권세빈의
-- V4__fk_constraints.sql에서 V1~V3 이후에 일괄로 건다. (todo.md Flyway 규칙)
--
-- 주의(통합 메모): 설계 스키마 시리즈는 V1__core(권세빈)·V2__database(정재환)·
-- V3__connector(백강민)·V4__fk_constraints(권세빈)이며, 기존 스캐폴드 마이그레이션
-- (V1 tenants / V2 datasources / V3 discovered_services)을 대체한다. 본 파일은 새 스키마
-- 기준으로 작성했으므로, 스캐폴드 V1~V3가 새 V1__core/V2__database로 교체된 뒤 함께 적용된다.

CREATE TABLE connector (
    id               UUID PRIMARY KEY,
    -- pipeline.id 참조(논리적). 물리 FK는 V4__fk_constraints.sql에서 추가.
    pipeline_id      UUID NOT NULL,
    cr_name          TEXT NOT NULL,
    kind             TEXT NOT NULL,          -- 'source' / 'sink'
    connector_class  TEXT NOT NULL,          -- Debezium / JDBC Sink class
    -- RUNNING / PARTIALLY_FAILED / FAILED / PAUSED / UNASSIGNED (부록 B.2, watcher 갱신)
    state            TEXT,
    tasks_max        INT  NOT NULL,          -- source=1, sink=3
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,            -- watch 시각
    CONSTRAINT connector_kind_chk CHECK (kind IN ('source', 'sink')),
    CONSTRAINT connector_cr_name_uq UNIQUE (cr_name)
);

-- pipeline별 connector 조회(EDA 1개 / CDC 2개) 최적화
CREATE INDEX idx_connector_pipeline ON connector(pipeline_id);
-- watcher가 CR 이름으로 빠르게 찾기 위한 인덱스(UNIQUE가 이미 인덱스를 만들지만 의도 명시)
CREATE INDEX idx_connector_state ON connector(state);
