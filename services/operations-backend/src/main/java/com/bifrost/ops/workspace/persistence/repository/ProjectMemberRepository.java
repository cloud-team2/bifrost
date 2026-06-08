package com.bifrost.ops.workspace.persistence.repository;

import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberEntity;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, ProjectMemberId> {
    boolean existsByIdWorkspaceIdAndIdUserId(UUID workspaceId, UUID userId);
    boolean existsByIdWorkspaceIdAndIdUserIdAndRoleIn(UUID workspaceId, UUID userId, List<Role> roles);
    Optional<ProjectMemberEntity> findByIdWorkspaceIdAndIdUserId(UUID workspaceId, UUID userId);
    List<ProjectMemberEntity> findByIdWorkspaceIdOrderByJoinedAtAsc(UUID workspaceId);
    List<ProjectMemberEntity> findByIdUserIdOrderByJoinedAtAsc(UUID userId);
    void deleteByIdWorkspaceIdAndIdUserId(UUID workspaceId, UUID userId);
}
