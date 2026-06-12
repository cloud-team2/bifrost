package com.bifrost.ops.internalops.dto;

import java.util.List;

/** sql_read — datasource에 read-only SELECT를 실행한 결과(#633 범용 read 프리미티브). */
public record SqlReadResult(
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        boolean truncated
) {
    public SqlReadResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
