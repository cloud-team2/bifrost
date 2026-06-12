package com.bifrost.ops.internalops.dto;

import java.util.List;

/** list_datasources — 프로젝트(workspace)의 datasource(DB) 목록과 헬스 요약(#633). */
public record DatasourceListResult(
        List<DatasourceSummary> datasources
) {
    public DatasourceListResult {
        datasources = datasources == null ? List.of() : List.copyOf(datasources);
    }

    /**
     * @param role connection 무관 — 파이프라인 사용 관점(source/sink/source+sink/unused).
     *             readiness 는 역할별로만 의미: 소스는 cdcReadinessStatus, 싱크는 sinkReadinessStatus.
     */
    public record DatasourceSummary(
            String id,
            String name,
            String dbType,
            String host,
            int port,
            String role,
            String connectionStatus,
            String cdcReadinessStatus,
            String sinkReadinessStatus
    ) {}
}
