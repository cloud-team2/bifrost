package com.bifrost.ops.workspace.kafka.dto;

public record KafkaPrincipalCreateRequest(
    String username,
    String secretRef
) {
}
