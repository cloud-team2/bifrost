-- DB 연결 헬스(#179): 주기 프로브 결과를 저장. cdc/sink readiness(등록 시점 1회)와 별개의
-- '지금 연결되는가' 축. connection_status: HEALTHY | UNREACHABLE | UNKNOWN.
ALTER TABLE datasources ADD COLUMN connection_status     VARCHAR(20);
ALTER TABLE datasources ADD COLUMN connection_error      VARCHAR(255);
ALTER TABLE datasources ADD COLUMN connection_checked_at TIMESTAMPTZ;
