package com.bifrost.ops.event.persistence.repository;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    List<EventEntity> findByTenantIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            UUID tenantId, List<EventLevel> levels, Instant createdAt, Pageable pageable);

    List<EventEntity> findByTenantIdAndPipelineIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            UUID tenantId, UUID pipelineId, List<EventLevel> levels, Instant createdAt, Pageable pageable);

    @Query("""
            SELECT e
            FROM EventEntity e
            WHERE e.tenantId = :tenantId
              AND e.pipelineId = :pipelineId
              AND e.level IN :levels
              AND e.createdAt >= :createdAt
              AND (
                    e.incidentId IN :incidentIds
                    OR LOWER(COALESCE(e.message, '')) LIKE LOWER(CONCAT('%', :connectorName, '%'))
                    OR LOWER(COALESCE(e.message, '')) LIKE LOWER(CONCAT('%', :consumerGroup, '%'))
                  )
            ORDER BY e.createdAt DESC
            """)
    List<EventEntity> findConnectorScopedEventsOrderByCreatedAtDesc(
            @Param("tenantId") UUID tenantId,
            @Param("pipelineId") UUID pipelineId,
            @Param("levels") List<EventLevel> levels,
            @Param("createdAt") Instant createdAt,
            @Param("incidentIds") List<UUID> incidentIds,
            @Param("connectorName") String connectorName,
            @Param("consumerGroup") String consumerGroup,
            Pageable pageable);
}
