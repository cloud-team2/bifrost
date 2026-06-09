package com.bifrost.ops.incident.dto;

import com.bifrost.ops.incident.persistence.entity.IncidentEntity;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID tenantId,
        String groupingKey,
        String severity,
        String status,
        String title,
        String rca,
        String sourceType,
        UUID sourceId,
        Instant openedAt,
        Instant resolvedAt
) {
    public static IncidentResponse from(IncidentEntity e) {
        return new IncidentResponse(
                e.getId(), e.getTenantId(), e.getGroupingKey(),
                e.getSeverity(), e.getStatus(), e.getTitle(), e.getRca(),
                e.getSourceType(), e.getSourceId(),
                e.getOpenedAt(), e.getResolvedAt());
    }
}
