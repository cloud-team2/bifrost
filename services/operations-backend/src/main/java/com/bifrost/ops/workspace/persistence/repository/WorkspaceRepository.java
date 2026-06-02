package com.bifrost.ops.workspace.persistence.repository;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {
    Optional<WorkspaceEntity> findByName(String name);
    Optional<WorkspaceEntity> findByNamespace(String namespace);
    boolean existsByName(String name);
    boolean existsByNamespace(String namespace);
}
