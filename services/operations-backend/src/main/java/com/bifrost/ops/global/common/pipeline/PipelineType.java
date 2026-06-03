package com.bifrost.ops.global.common.pipeline;

/**
 * 파이프라인 종류.
 * CDC: Source DB → Kafka (events). 컨슈머 앱이 직접 구독.
 * SYNC: Source DB → Kafka → Sink DB (Phase 2).
 */
public enum PipelineType {
    CDC,
    SYNC
}
