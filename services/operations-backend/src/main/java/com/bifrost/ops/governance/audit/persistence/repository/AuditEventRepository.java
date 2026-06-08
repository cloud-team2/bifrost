package com.bifrost.ops.governance.audit.persistence.repository;

import com.bifrost.ops.governance.audit.persistence.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** {@code audit_events} 접근(#70). append-only. */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    List<AuditEventEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
