package com.bifrost.ops.incident.dto;

import com.bifrost.ops.event.dto.EventResponse;

import java.util.List;
import java.util.UUID;

public record IncidentDetailResponse(
        IncidentResponse incident,
        List<EventResponse> events,
        List<UUID> impactPipelineIds,
        List<IncidentReportResponse> reports
) {
}
