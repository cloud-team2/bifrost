package com.bifrost.ops.database.inspector;

import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.pipeline.TableRef;
import com.bifrost.ops.provisioning.dto.DebeziumConnectorConfig;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * DB 추상화 레이어. PostgreSQL·MariaDB의 차이를 격리한다.
 *
 * <p>사용 패턴 (try-with-resources):
 * <pre>
 * try (DatabaseInspector inspector = factory.create(datasource, password)) {
 *     CdcReadinessResponse r = inspector.checkSourceReadiness();
 * }
 * </pre>
 */
public interface DatabaseInspector extends AutoCloseable {

    DbType getType();

    ConnectionTestResult testConnection();

    /** 모든 사용자 스키마의 테이블·컬럼 정보. */
    List<TableInfo> listTables();

    /** 엔진 통계 뷰와 실제 probe query 기반 DB 지표. */
    MetricsSnapshot collectMetrics();

    /** CDC Source 준비도 점검 (wal_level, binlog 등). */
    CdcReadinessResponse checkSourceReadiness();

    /** CDC Sink 준비도 점검 (INSERT/UPDATE/DELETE/CREATE 권한). */
    CdcReadinessResponse checkSinkReadiness();

    /** 특정 테이블이 CDC source로 사용 가능한지 (PG: REPLICA IDENTITY 확인). */
    boolean checkTableCDCReadiness(String schema, String table);

    DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec);

    @Override
    void close();

    // ---------- 내부 데이터 클래스 ----------

    record ConnectionTestResult(boolean success, String message, Duration latency) {}

    record MetricsSnapshot(
            double tps,
            double queryResponseMs,
            Double queryResponseP95Ms,
            int activeConnections
    ) {}

    record TableInfo(
            String schema,
            String name,
            Long approximateRowCount,
            Long totalSizeBytes,
            boolean hasPrimaryKey,
            List<ColumnInfo> columns,
            List<String> primaryKeyColumns
    ) {}

    record ColumnInfo(
            String name,
            String dataType,
            boolean nullable,
            boolean isPrimaryKey,
            boolean indexed
    ) {}

    record PipelineSpec(
            UUID pipelineId,
            UUID tenantId,
            String projectKey,
            String topicPrefix,
            String secretRef,
            List<TableRef> tables
    ) {}
}
