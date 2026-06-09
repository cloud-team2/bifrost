package com.bifrost.ops.governance.approval.persistence.repository;

import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<ApprovalEntity, UUID> {
}
