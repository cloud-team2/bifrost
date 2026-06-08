package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.Role;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberResponse(
    UUID workspaceId,
    UUID userId,
    String email,
    Role role,
    Instant joinedAt
) {}
