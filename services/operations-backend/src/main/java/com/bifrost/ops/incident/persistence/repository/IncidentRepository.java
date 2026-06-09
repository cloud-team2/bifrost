package com.bifrost.ops.incident.persistence.repository;

import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {
    List<IncidentEntity> findByTenantIdOrderByOpenedAtDesc(UUID tenantId);
    List<IncidentEntity> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, String status);
    long countByTenantIdAndStatus(UUID tenantId, String status);
    Optional<IncidentEntity> findByTenantIdAndGroupingKeyAndStatus(UUID tenantId, String groupingKey, String status);
}
