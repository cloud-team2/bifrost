package com.bifrost.ops.workspace.kafka;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KafkaPrincipalRepository extends JpaRepository<KafkaPrincipalEntity, UUID> {
    List<KafkaPrincipalEntity> findByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);
    Optional<KafkaPrincipalEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    boolean existsByWorkspaceIdAndUsername(UUID workspaceId, String username);
}
