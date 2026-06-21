package com.bifrost.ops.internalops.dto;

import java.util.List;

/**
 * get_incident_summary 응답(#474). RCA 도구가 영향 파이프라인의 커넥터를 바로 체이닝할 수 있도록
 * {@code connectors}(source/sink 커넥터명)를 포함한다(#925).
 */
public record IncidentSummaryResult(
        String incidentId,
        String status,
        String note,
        List<ConnectorRef> connectors) {

    /** 인시던트 영향 파이프라인의 Kafka Connect 커넥터 — get_connector_task_trace 등 체이닝 대상. */
    public record ConnectorRef(String name, String role, String pipelineId, String pipelineName) {}

    public static IncidentSummaryResult stub(String incidentId) {
        return new IncidentSummaryResult(incidentId, "UNKNOWN",
                "incident read model pending — dependency on monitoring integration",
                List.of());
    }
}
