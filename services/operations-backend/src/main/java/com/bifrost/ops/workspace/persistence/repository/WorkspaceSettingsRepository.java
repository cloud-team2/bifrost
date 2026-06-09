package com.bifrost.ops.workspace.persistence.repository;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkspaceSettingsRepository extends JpaRepository<WorkspaceSettingsEntity, UUID> {
}
