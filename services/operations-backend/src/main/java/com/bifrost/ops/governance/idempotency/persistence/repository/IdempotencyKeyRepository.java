package com.bifrost.ops.governance.idempotency.persistence.repository;

import com.bifrost.ops.governance.idempotency.persistence.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {
    Optional<IdempotencyKeyEntity> findByIdemKeyAndTenantId(String idemKey, UUID tenantId);
}
