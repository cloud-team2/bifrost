package com.bifrost.ops.event.persistence.repository;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** {@code events} 접근(#70). tenant scope, 최신순. level/pipelineId/incidentId 필터. */
public interface EventRepository extends JpaRepository<EventEntity, UUID> {

    List<EventEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<EventEntity> findByTenantIdAndLevelOrderByCreatedAtDesc(UUID tenantId, EventLevel level);

    List<EventEntity> findByTenantIdAndPipelineIdOrderByCreatedAtDesc(UUID tenantId, UUID pipelineId);

    List<EventEntity> findByTenantIdAndLevelAndPipelineIdOrderByCreatedAtDesc(
            UUID tenantId, EventLevel level, UUID pipelineId);

    List<EventEntity> findByTenantIdAndIncidentIdOrderByCreatedAtDesc(UUID tenantId, UUID incidentId);

    List<EventEntity> findByTenantIdAndLevelAndIncidentIdOrderByCreatedAtDesc(
            UUID tenantId, EventLevel level, UUID incidentId);

    List<EventEntity> findByTenantIdAndPipelineIdAndIncidentIdOrderByCreatedAtDesc(
            UUID tenantId, UUID pipelineId, UUID incidentId);

    List<EventEntity> findByTenantIdAndLevelAndPipelineIdAndIncidentIdOrderByCreatedAtDesc(
            UUID tenantId, EventLevel level, UUID pipelineId, UUID incidentId);
}
