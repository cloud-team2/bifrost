package com.bifrost.ops.governance.changemanagement.persistence.repository;

import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChangeTicketRepository extends JpaRepository<ChangeTicketEntity, UUID> {
}
