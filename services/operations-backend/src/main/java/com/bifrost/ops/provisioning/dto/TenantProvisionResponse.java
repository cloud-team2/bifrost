package com.bifrost.ops.provisioning.dto;

import java.time.Instant;
import java.util.UUID;

public record TenantProvisionResponse(
    UUID tenantId,
    String namespace,
    String kafkaUserName,
    String kafkaUserSecretName,
    Status status,
    Instant provisionedAt
) {
    public enum Status {
        PROVISIONED,
        ALREADY_EXISTED,
        FAILED
    }
}
