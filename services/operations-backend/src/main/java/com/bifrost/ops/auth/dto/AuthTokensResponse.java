package com.bifrost.ops.auth.dto;

import java.util.UUID;

public record AuthTokensResponse(
    String accessToken,
    String tokenType,
    long expiresInSeconds,
    UUID userId,
    UUID workspaceId
) {}
