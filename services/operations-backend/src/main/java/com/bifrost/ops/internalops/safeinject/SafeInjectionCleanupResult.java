package com.bifrost.ops.internalops.safeinject;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SafeInjectionCleanupResult(
        @JsonProperty("run_id")
        String runId,
        @JsonProperty("deleted_k8s_connectors")
        int deletedK8sConnectors,
        @JsonProperty("deleted_metadata_rows")
        int deletedMetadataRows,
        @JsonProperty("residual_count")
        int residualCount,
        @JsonProperty("residuals")
        List<String> residuals
) {
}
