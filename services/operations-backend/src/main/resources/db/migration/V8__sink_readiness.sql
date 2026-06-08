-- Phase 2 sink readiness 컬럼 추가 (#106).
-- datasources에 sink 준비도 상태·리포트 컬럼을 add-only로 보강한다.
ALTER TABLE datasources ADD COLUMN sink_readiness_status  VARCHAR(20);
ALTER TABLE datasources ADD COLUMN sink_readiness_report  JSONB;
