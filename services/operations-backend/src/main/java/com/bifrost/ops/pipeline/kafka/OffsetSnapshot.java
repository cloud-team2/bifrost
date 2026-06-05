package com.bifrost.ops.pipeline.kafka;

import org.apache.kafka.common.TopicPartition;

import java.time.Instant;
import java.util.Map;

/** 특정 시점의 토픽 파티션별 offset 스냅샷. */
public record OffsetSnapshot(
        Instant collectedAt,
        Map<TopicPartition, Long> endOffsets,
        Map<TopicPartition, Long> committedOffsets  // 파티션별 max(consumer groups)
) {}
