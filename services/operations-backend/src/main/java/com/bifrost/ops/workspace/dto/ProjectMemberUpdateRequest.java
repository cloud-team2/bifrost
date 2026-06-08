package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.Role;
import jakarta.validation.constraints.NotNull;

public record ProjectMemberUpdateRequest(
    @NotNull Role role
) {}
