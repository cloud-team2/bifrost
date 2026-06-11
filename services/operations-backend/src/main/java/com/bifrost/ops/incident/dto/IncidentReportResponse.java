package com.bifrost.ops.incident.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record IncidentReportResponse(
        String id,
        String runId,
        String incidentId,
        String rootCauseId,
        Double confidence,
        boolean verified,
        JsonNode body,
        Instant createdAt
) {
}
