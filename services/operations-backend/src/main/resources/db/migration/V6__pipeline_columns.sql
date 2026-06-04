-- 파이프라인 플랫폼 API(#71): EDA/CDC 생성에 필요한 컬럼을 add-only로 보강한다(V1~V5 불변).
-- 기존 pipelines(V2)에는 source_datasource_id·tables·source_connector_name·topic_name·
-- status·status_message·status_updated_at만 있다. 아래를 추가한다.
ALTER TABLE pipelines ADD COLUMN pattern VARCHAR(20);            -- FAN_OUT(EDA) / DIRECT(CDC)
ALTER TABLE pipelines ADD COLUMN sink_datasource_id UUID REFERENCES datasources(id); -- DIRECT만
ALTER TABLE pipelines ADD COLUMN sink_connector_name VARCHAR(255);
ALTER TABLE pipelines ADD COLUMN schema_name VARCHAR(255);      -- 단일 테이블 스키마
ALTER TABLE pipelines ADD COLUMN table_name VARCHAR(255);       -- 단일 테이블 이름

-- 하나의 pipeline = 하나의 topic/table 원칙: 동일 워크스페이스에서 같은 source DB + schema + table +
-- pattern 중복 생성을 DB 레벨에서도 막는다(서비스 검증과 이중 방어, 부분 인덱스로 sink 무관).
CREATE UNIQUE INDEX uq_pipelines_source_table_pattern
    ON pipelines (tenant_id, source_datasource_id, schema_name, table_name, pattern);

CREATE INDEX idx_pipelines_sink ON pipelines(sink_datasource_id);
