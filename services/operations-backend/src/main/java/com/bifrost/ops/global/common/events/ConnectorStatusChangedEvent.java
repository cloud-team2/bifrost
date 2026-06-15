package com.bifrost.ops.global.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bifrost.ops.global.common.pipeline.PipelineStatus;

/**
 * orchestrator → runtime Kafka 토픽 `platform.internal.connector-status`
 * (KafkaTopic CR 객체명과 별개)
 * core가 구독해서 MetaDB 업데이트 + WebSocket push.
 */
public record ConnectorStatusChangedEvent(
    UUID eventId,
    String eventType,
    Instant timestamp,
    UUID tenantId,
    String source,
    Payload payload
) {
    public static final String EVENT_TYPE = "CONNECTOR_STATUS_CHANGED";
    
    public record Payload(
        UUID pipelineId,
        String kafkaConnectorName,
        String namespace,
        PipelineStatus previousStatus,
        PipelineStatus currentStatus,
        List<TaskState> tasks,
        String message
    ) {}
    
    public record TaskState(
        int id,
        String state,
        String trace
    ) {}
}
