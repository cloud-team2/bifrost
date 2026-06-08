package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProjectMemberAddRequest(
    @NotBlank @Email String email,
    @NotNull Role role
) {}
