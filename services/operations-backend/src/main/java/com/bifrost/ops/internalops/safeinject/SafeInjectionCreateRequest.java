package com.bifrost.ops.internalops.safeinject;

public record SafeInjectionCreateRequest(
        String runId,
        String fault,
        String connectorName
) {
}
