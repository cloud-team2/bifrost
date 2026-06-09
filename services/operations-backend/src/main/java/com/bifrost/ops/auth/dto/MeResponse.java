package com.bifrost.ops.auth.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;

import java.time.Instant;
import java.util.UUID;

public record MeResponse(
    UUID userId,
    String email,
    String name,
    String role,
    Instant joinedAt,
    Instant lastLoginAt,
    UUID workspaceId,
    String workspaceName,
    String namespace,
    WorkspaceEntity.Status workspaceStatus
) {}
