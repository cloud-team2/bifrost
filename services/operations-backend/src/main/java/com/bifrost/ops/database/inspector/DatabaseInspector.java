package com.bifrost.ops.database.inspector;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.DebeziumConnectorConfig;
import com.bifrost.ops.global.common.pipeline.TableRef;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * DB 추상화 레이어. PG와 MariaDB의 차이를 격리.
 * 
 * 사용 패턴 (try-with-resources):
 * <pre>
 * try (DatabaseInspector inspector = factory.create(datasource)) {
 *     CDCReadinessReport report = inspector.checkCDCReadiness();
 *     // ...
 * }
 * </pre>
 */
public interface DatabaseInspector extends AutoCloseable {

    DbType getType();

    ConnectionTestResult testConnection();

    /** 모든 스키마의 모든 테이블 + 컬럼 정보 (MVP 단순화) */
    List<TableInfo> listTables();

    CDCReadinessReport checkCDCReadiness();

    /** 특정 테이블의 CDC readiness (PG의 REPLICA IDENTITY 등) */
    TableCDCReadiness checkTableCDCReadiness(String schema, String table);

    DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec);

    @Override
    void close();

    // ---------- 데이터 클래스 ----------

    record ConnectionTestResult(
        boolean success,
        String message,
        Duration latency
    ) {}

    record TableInfo(
        String schema,
        String name,
        long approximateRowCount,
        boolean hasPrimaryKey,
        List<ColumnInfo> columns,
        List<String> primaryKeyColumns
    ) {}

    record ColumnInfo(
        String name,
        String dataType,
        boolean nullable,
        boolean isPrimaryKey
    ) {}

    record CDCReadinessReport(
        ReadinessStatus status,
        List<Check> checks,
        Instant checkedAt
    ) {
        public enum ReadinessStatus { GREEN, YELLOW, RED }
        
        public record Check(
            String key,
            boolean passed,
            String message,
            String remediation
        ) {}
    }

    record TableCDCReadiness(
        String schema,
        String table,
        boolean ready,
        String reason
    ) {}

    record PipelineSpec(
        java.util.UUID pipelineId,
        java.util.UUID tenantId,
        String topicPrefix,
        String secretRef,
        List<TableRef> tables
    ) {}
}
