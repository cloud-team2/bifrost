package com.bifrost.ops.auth.jwt;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID tenantId, String email) {}
