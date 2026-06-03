package com.bifrost.ops.database.inspector.postgres;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.DebeziumConnectorConfig;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;

import java.util.List;

/**
 * PostgreSQL용 Inspector.
 * 
 * CDC readiness 검사 항목:
 * - wal_level = logical
 * - REPLICATION 권한
 * - max_replication_slots 여유
 * - max_wal_senders >= 1
 * - 대상 테이블의 REPLICA IDENTITY = FULL (SYNC 안전성)
 */
public class PostgresInspector implements DatabaseInspector {

    private final DatasourceEntity datasource;
    private final String password;

    public PostgresInspector(DatasourceEntity datasource, String password) {
        this.datasource = datasource;
        this.password = password;
        // TODO: HikariCP DataSource 생성, JdbcTemplate 초기화
    }

    @Override
    public DbType getType() {
        return DbType.POSTGRESQL;
    }

    @Override
    public ConnectionTestResult testConnection() {
        // TODO: JDBC 연결 시도, 결과 반환
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<TableInfo> listTables() {
        // TODO: pg_class + pg_namespace + pg_attribute 조회
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public CDCReadinessReport checkCDCReadiness() {
        // TODO: SHOW wal_level, pg_roles 조회 등
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public TableCDCReadiness checkTableCDCReadiness(String schema, String table) {
        // TODO: relreplident 조회
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec) {
        // TODO: PG용 Debezium config 생성
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void close() {
        // TODO: HikariCP 닫기
    }
}
