package com.bifrost.ops.governance.changemanagement.persistence.repository;

import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ChangeTicketRepository extends JpaRepository<ChangeTicketEntity, UUID> {

    Optional<ChangeTicketEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ChangeTicketEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ChangeTicketEntity t where t.id = :id and t.tenantId = :tenantId")
    Optional<ChangeTicketEntity> findByIdAndTenantIdForUpdate(@Param("id") UUID id,
                                                              @Param("tenantId") UUID tenantId);
}
