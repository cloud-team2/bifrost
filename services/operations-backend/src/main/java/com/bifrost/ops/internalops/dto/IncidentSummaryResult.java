package com.bifrost.ops.internalops.dto;

public record IncidentSummaryResult(String incidentId, String status, String note) {
    public static IncidentSummaryResult stub(String incidentId) {
        return new IncidentSummaryResult(incidentId, "UNKNOWN",
                "incident read model pending — dependency on monitoring integration");
    }
}
