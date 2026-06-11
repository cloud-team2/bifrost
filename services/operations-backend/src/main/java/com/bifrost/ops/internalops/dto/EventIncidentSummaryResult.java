package com.bifrost.ops.internalops.dto;

import java.time.Instant;
import java.util.List;

public record EventIncidentSummaryResult(
        String window,
        String level,
        long openIncidents,
        long criticalIncidents,
        List<CriticalIncident> critical,
        List<WarningEvent> warnings
) {
    public EventIncidentSummaryResult {
        critical = critical == null ? List.of() : List.copyOf(critical);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record CriticalIncident(
            String incidentId,
            String severity,
            String status,
            String title,
            Instant openedAt
    ) {}

    public record WarningEvent(
            String eventId,
            String level,
            String type,
            String message,
            String pipelineId,
            String incidentId,
            Instant createdAt
    ) {}
}
