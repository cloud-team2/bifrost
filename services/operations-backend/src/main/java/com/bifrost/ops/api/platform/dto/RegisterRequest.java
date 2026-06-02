package com.bifrost.ops.api.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 8, max = 128) String password,
    @NotBlank @Size(min = 2, max = 100) String workspaceName,
    @NotBlank @Size(min = 3, max = 63)
    @Pattern(regexp = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$",
             message = "namespace는 소문자/숫자/하이픈만, 처음·끝은 영숫자")
    String namespace
) {}
