package com.bifrost.ops.internalops.dto;

import java.util.List;

public record PipelineStatusListResult(
        List<PipelineStatusSummary> pipelines
) {
    public PipelineStatusListResult {
        pipelines = pipelines == null ? List.of() : List.copyOf(pipelines);
    }

    public record PipelineStatusSummary(
            String id,
            String name,
            String status,
            Long lag,
            String error
    ) {}
}
