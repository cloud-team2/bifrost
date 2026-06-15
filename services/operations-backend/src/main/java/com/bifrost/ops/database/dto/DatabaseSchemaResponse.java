package com.bifrost.ops.database.dto;

import java.util.List;

/**
 * 스키마 조회 응답(#28, FR-016, database-registry.md §6). 등록된 DB의 테이블·컬럼 메타데이터.
 *
 * <p>엔진 차이를 흡수하기 위해 JDBC {@code DatabaseMetaData}로 읽는다(PG·MariaDB 공통).
 */
public record DatabaseSchemaResponse(List<TableSchema> tables) {

    public record TableSchema(
            String schema,
            String name,
            long approximateRowCount,
            long totalSizeBytes,
            List<ColumnSchema> columns
    ) {
    }

    public record ColumnSchema(
            String name,
            String type,
            boolean nullable,
            boolean primaryKey,
            boolean indexed
    ) {
    }
}
