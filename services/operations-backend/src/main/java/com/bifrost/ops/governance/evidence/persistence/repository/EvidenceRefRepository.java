package com.bifrost.ops.governance.evidence.persistence.repository;

import com.bifrost.ops.governance.evidence.persistence.entity.EvidenceRefEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceRefRepository extends JpaRepository<EvidenceRefEntity, UUID> {
    List<EvidenceRefEntity> findByMutationIdOrderByCreatedAtAsc(UUID mutationId);
}
