package com.bifrost.ops.workspace.kafka.dto;

import com.bifrost.ops.workspace.kafka.KafkaPrincipalEntity;

import java.time.Instant;
import java.util.UUID;

public record KafkaPrincipalResponse(
    UUID id,
    UUID workspaceId,
    String username,
    String secretRef,
    String status,
    Instant createdAt,
    Instant deactivatedAt,
    Instant revokedAt
) {
    public static KafkaPrincipalResponse from(KafkaPrincipalEntity entity) {
        return new KafkaPrincipalResponse(
            entity.getId(),
            entity.getWorkspaceId(),
            entity.getUsername(),
            entity.getSecretRef(),
            entity.getStatus().name(),
            entity.getCreatedAt(),
            entity.getDeactivatedAt(),
            entity.getRevokedAt()
        );
    }
}
