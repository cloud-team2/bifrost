package com.bifrost.ops.monitoring.dto;

/** 워크스페이스 전체 health 집계(S5). */
public record OverviewResponse(
        long totalPipelines,
        long runningPipelines,
        long errorPipelines,
        long healthyDatabases,
        long unreachableDatabases,
        long openIncidents,
        long totalConnectors,
        long failedConnectors
) {}
