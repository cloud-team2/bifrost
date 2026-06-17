package com.bifrost.ops.incident.persistence.repository;

import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {
    List<IncidentEntity> findByTenantIdOrderByOpenedAtDesc(UUID tenantId);
    List<IncidentEntity> findByTenantIdOrderByOpenedAtDesc(UUID tenantId, Pageable pageable);
    List<IncidentEntity> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, String status);
    List<IncidentEntity> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, String status, Pageable pageable);
    List<IncidentEntity> findByTenantIdAndSeverityOrderByOpenedAtDesc(UUID tenantId, String severity, Pageable pageable);
    List<IncidentEntity> findByTenantIdAndStatusAndSeverityOrderByOpenedAtDesc(
            UUID tenantId, String status, String severity, Pageable pageable);
    long countByTenantIdAndStatus(UUID tenantId, String status);
    Optional<IncidentEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    List<IncidentEntity> findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
            UUID tenantId, String groupingKey, String status);
    /** 활성(OPEN·INVESTIGATING) 인시던트 — 생성/에스컬레이션/복구 처리에 사용(#558). */
    List<IncidentEntity> findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
            UUID tenantId, String groupingKey, List<String> statuses);
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
    Object lockIncidentGroup(@Param("lockKey") String lockKey);
    List<IncidentEntity> findByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
            UUID tenantId, String status, List<String> severities, Instant openedAt);

    @Query("""
            SELECT i
            FROM IncidentEntity i
            WHERE i.tenantId = :tenantId
              AND (:status IS NULL OR i.status = :status)
              AND (:severity IS NULL OR i.severity = :severity)
              AND (i.groupingKey IN :groupingKeys OR i.sourceId IN :sourceIds)
            ORDER BY i.openedAt DESC
            """)
    List<IncidentEntity> findScopedByTenantIdOrderByOpenedAtDesc(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("severity") String severity,
            @Param("groupingKeys") List<String> groupingKeys,
            @Param("sourceIds") List<UUID> sourceIds,
            Pageable pageable);

    @Query("""
            SELECT i
            FROM IncidentEntity i
            WHERE i.tenantId = :tenantId
              AND (:status IS NULL OR i.status = :status)
              AND (:severity IS NULL OR i.severity = :severity)
              AND (i.groupingKey IN :groupingKeys OR i.sourceId IN :sourceIds)
              AND (
                    i.sourceType IS NULL
                    OR LOWER(i.sourceType) <> 'database'
                    OR EXISTS (
                        SELECT e.id
                        FROM EventEntity e
                        WHERE e.tenantId = i.tenantId
                          AND e.pipelineId = :pipelineId
                          AND e.incidentId = i.id
                    )
                  )
            ORDER BY i.openedAt DESC
            """)
    List<IncidentEntity> findScopedAlertsByTenantIdOrderByOpenedAtDesc(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("severity") String severity,
            @Param("groupingKeys") List<String> groupingKeys,
            @Param("sourceIds") List<UUID> sourceIds,
            @Param("pipelineId") UUID pipelineId,
            Pageable pageable);

    @Query("""
            SELECT i
            FROM IncidentEntity i
            WHERE i.tenantId = :tenantId
              AND i.status = :status
              AND i.severity IN :severities
              AND i.openedAt >= :openedAt
              AND (i.groupingKey IN :groupingKeys OR i.sourceId IN :sourceIds)
            ORDER BY i.openedAt DESC
            """)
    List<IncidentEntity> findScopedByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("severities") List<String> severities,
            @Param("openedAt") Instant openedAt,
            @Param("groupingKeys") List<String> groupingKeys,
            @Param("sourceIds") List<UUID> sourceIds);
}
