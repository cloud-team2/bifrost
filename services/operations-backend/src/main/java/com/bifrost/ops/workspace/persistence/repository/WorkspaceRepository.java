package com.bifrost.ops.workspace.persistence.repository;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {
    Optional<WorkspaceEntity> findByName(String name);
    Optional<WorkspaceEntity> findByNamespace(String namespace);
    boolean existsByName(String name);
    boolean existsByNamespace(String namespace);

    /** 소유 기반 다중 워크스페이스(#72): 사용자가 소유한 워크스페이스 목록. */
    List<WorkspaceEntity> findByOwnerUserIdOrderByCreatedAt(UUID ownerUserId);

    /** scope 검증용: 해당 워크스페이스가 사용자 소유인지. */
    boolean existsByIdAndOwnerUserId(UUID id, UUID ownerUserId);
}
