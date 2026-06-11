package com.bifrost.ops.event;

import com.bifrost.ops.event.dto.EventResponse;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 운영 이벤트 기록·조회(#70, 최소 구현, 부록 B.6).
 */
@Service
public class EventService {

    private final EventRepository repository;

    public EventService(EventRepository repository) {
        this.repository = repository;
    }

    /** 이벤트 1건 기록(append-only). */
    public void record(UUID tenantId, UUID pipelineId, EventLevel level, String type, String message) {
        EventEntity e = new EventEntity();
        e.setTenantId(tenantId);
        e.setPipelineId(pipelineId);
        e.setLevel(level);
        e.setType(type);
        e.setMessage(message);
        repository.save(e);
    }

    /** incident_id·category 포함 이벤트 기록(S2). */
    public void recordWithIncident(UUID tenantId, UUID pipelineId, EventLevel level, String type,
                                   String message, UUID incidentId, String category) {
        EventEntity e = new EventEntity();
        e.setTenantId(tenantId);
        e.setPipelineId(pipelineId);
        e.setLevel(level);
        e.setType(type);
        e.setMessage(message);
        e.setIncidentId(incidentId);
        e.setCategory(category);
        repository.save(e);
    }

    /** 워크스페이스 이벤트 목록. level/pipelineId/incidentId 선택 필터. */
    public List<EventResponse> list(UUID tenantId, EventLevel level, UUID pipelineId, UUID incidentId) {
        List<EventEntity> rows;
        if (level != null && pipelineId != null && incidentId != null) {
            rows = repository.findByTenantIdAndLevelAndPipelineIdAndIncidentIdOrderByCreatedAtDesc(
                    tenantId, level, pipelineId, incidentId);
        } else if (level != null && pipelineId != null) {
            rows = repository.findByTenantIdAndLevelAndPipelineIdOrderByCreatedAtDesc(tenantId, level, pipelineId);
        } else if (level != null && incidentId != null) {
            rows = repository.findByTenantIdAndLevelAndIncidentIdOrderByCreatedAtDesc(tenantId, level, incidentId);
        } else if (pipelineId != null && incidentId != null) {
            rows = repository.findByTenantIdAndPipelineIdAndIncidentIdOrderByCreatedAtDesc(tenantId, pipelineId, incidentId);
        } else if (level != null) {
            rows = repository.findByTenantIdAndLevelOrderByCreatedAtDesc(tenantId, level);
        } else if (pipelineId != null) {
            rows = repository.findByTenantIdAndPipelineIdOrderByCreatedAtDesc(tenantId, pipelineId);
        } else if (incidentId != null) {
            rows = repository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(tenantId, incidentId);
        } else {
            rows = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return rows.stream().map(EventResponse::from).toList();
    }
}
