package com.bifrost.ops.incident.persistence.repository;

import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
    Optional<IncidentEntity> findByTenantIdAndGroupingKeyAndStatus(UUID tenantId, String groupingKey, String status);
    List<IncidentEntity> findByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
            UUID tenantId, String status, List<String> severities, Instant openedAt);
}
