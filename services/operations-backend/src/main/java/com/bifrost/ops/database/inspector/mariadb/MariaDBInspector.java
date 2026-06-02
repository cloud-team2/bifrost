package com.bifrost.ops.database.inspector.mariadb;

import com.bifrost.ops.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.DebeziumConnectorConfig;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;

import java.util.List;

/**
 * MariaDB용 Inspector.
 * 
 * CDC readiness 검사 항목:
 * - log_bin = ON
 * - binlog_format = ROW
 * - binlog_row_image = FULL
 * - server_id > 0
 * - REPLICATION SLAVE, REPLICATION CLIENT 권한
 */
public class MariaDBInspector implements DatabaseInspector {

    private final DatasourceEntity datasource;
    private final String password;

    public MariaDBInspector(DatasourceEntity datasource, String password) {
        this.datasource = datasource;
        this.password = password;
        // TODO: HikariCP DataSource 생성, JdbcTemplate 초기화
    }

    @Override
    public DbType getType() {
        return DbType.MARIADB;
    }

    @Override
    public ConnectionTestResult testConnection() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<TableInfo> listTables() {
        // TODO: information_schema.tables, information_schema.columns
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public CDCReadinessReport checkCDCReadiness() {
        // TODO: SHOW VARIABLES, SHOW GRANTS
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public TableCDCReadiness checkTableCDCReadiness(String schema, String table) {
        // MariaDB는 테이블별 설정 없음, 전역 binlog_row_image만 확인
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec) {
        // TODO: MariaDB용 Debezium config + server.id, schema history topic
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void close() {
        // TODO
    }
}
