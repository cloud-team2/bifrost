package com.bifrost.ops.governance.approval.persistence.repository;

import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<ApprovalEntity, UUID> {

    Optional<ApprovalEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ApprovalEntity> findByTenantIdAndDecisionIgnoreCase(UUID tenantId, String decision, Pageable pageable);

    List<ApprovalEntity> findByTenantIdAndDecisionIgnoreCaseAndActor(
            UUID tenantId, String decision, String actor, Pageable pageable);
}
