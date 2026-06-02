package com.bifrost.ops.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * orchestrator → Kafka 토픽 `platform.internal.service-discovered`
 * 새 Consumer Group이 발견되면 발행.
 */
public record ServiceDiscoveredEvent(
    UUID eventId,
    String eventType,
    Instant timestamp,
    UUID tenantId,
    String source,
    Payload payload
) {
    public static final String EVENT_TYPE = "SERVICE_DISCOVERED";
    
    public record Payload(
        String consumerGroupId,
        List<String> subscribedTopics,
        int memberCount,
        Instant discoveredAt
    ) {}
}
