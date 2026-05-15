package com.platform.common.orchestrator;

import java.util.Map;
import java.util.UUID;

/**
 * core → orchestrator: Pipeline 생성 요청.
 * orchestrator가 KafkaTopic + KafkaConnector CRD 생성.
 */
public record PipelineCreateRequest(
    UUID pipelineId,
    UUID tenantId,
    String namespace,
    String name,
    TopicSpec topic,
    SourceConnectorSpec sourceConnector
) {
    public record TopicSpec(
        String name,
        int partitions,
        int replicationFactor,
        Map<String, String> config
    ) {}
    
    public record SourceConnectorSpec(
        String name,
        String connectorClass,
        String secretRef,
        Map<String, String> config
    ) {}
}
