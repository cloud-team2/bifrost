package com.bifrost.ops.workspace.dto;

import jakarta.validation.constraints.Size;

public record WorkspaceUpdateRequest(
    @Size(min = 2, max = 100) String name,
    @Size(max = 50) String timezone
) {}
