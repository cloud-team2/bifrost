package com.bifrost.ops.internalops.safeinject;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

public record SafeInjectionCreateResult(
        @JsonProperty("run_id")
        String runId,
        String fault,
        @JsonProperty("expected_root_cause_id")
        String expectedRootCauseId,
        @JsonProperty("pipeline_id")
        UUID pipelineId,
        @JsonProperty("datasource_id")
        UUID datasourceId,
        @JsonProperty("metadata_persisted")
        boolean metadataPersisted,
        @JsonProperty("connector_name")
        String connectorName,
        String namespace,
        Map<String, String> labels,
        @JsonProperty("safe_scope")
        String safeScope
) {
}
