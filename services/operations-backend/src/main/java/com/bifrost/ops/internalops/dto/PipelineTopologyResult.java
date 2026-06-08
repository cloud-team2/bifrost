package com.bifrost.ops.internalops.dto;

import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.PipelineResponse;

import java.util.List;
import java.util.UUID;

/**
 * get_pipeline_topology tool의 result payload.
 *
 * <p>source / sink / connectors / topics / status 를 한 번에 담는다.
 */
public record PipelineTopologyResult(
        UUID pipelineId,
        String name,
        String pattern,
        String status,
        String topic,
        UUID sourceDbId,
        UUID sinkDbId,
        String sourceConnector,
        String sinkConnector,
        List<ConnectorResponse> connectors
) {
    public static PipelineTopologyResult of(PipelineResponse p, List<ConnectorResponse> connectors) {
        return new PipelineTopologyResult(
                p.id(),
                p.name(),
                p.pattern(),
                p.status(),
                p.topic(),
                p.sourceDbId(),
                p.sinkDbId(),
                p.sourceConnector(),
                p.sinkConnector(),
                connectors
        );
    }
}
