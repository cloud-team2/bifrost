package com.platform.common.orchestrator;

import java.time.Instant;
import java.util.UUID;

public record PipelineCreateResponse(
    UUID pipelineId,
    String kafkaConnectorName,
    String kafkaTopicName,
    String status,
    Instant createdAt
) {}
