package com.bifrost.ops.api.platform.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;

import java.util.UUID;

public record MeResponse(
    UUID userId,
    String email,
    UUID workspaceId,
    String workspaceName,
    String namespace,
    WorkspaceEntity.Status workspaceStatus
) {}
