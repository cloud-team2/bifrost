package com.bifrost.ops.internalops.dto;

import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Agent list_alerts item view backed by an incident row. */
public record AlertSummaryResult(
        @JsonProperty("alert_id") String alertId,
        String severity,
        String status,
        String summary,
        Map<String, String> labels,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("incident_id") String incidentId
) {
    public static AlertSummaryResult fromIncident(IncidentEntity incident) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (incident.getSourceType() != null && !incident.getSourceType().isBlank()) {
            labels.put("source_type", incident.getSourceType());
        }

        String id = incident.getId().toString();
        return new AlertSummaryResult(
                id,
                incident.getSeverity(),
                incident.getStatus(),
                incident.getTitle(),
                labels,
                incident.getOpenedAt(),
                id);
    }
}
