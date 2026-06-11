package com.bifrost.ops.event.dto;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;

import java.time.Instant;
import java.util.UUID;

/** 이벤트 응답(#70). */
public record EventResponse(
    UUID id,
    UUID pipelineId,
    UUID incidentId,
    EventLevel level,
    String type,
    String message,
    Instant createdAt
) {
    public static EventResponse from(EventEntity e) {
        return new EventResponse(e.getId(), e.getPipelineId(), e.getIncidentId(), e.getLevel(),
                e.getType(), e.getMessage(), e.getCreatedAt());
    }
}
