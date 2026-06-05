package com.bifrost.ops.pipeline.dto;

/**
 * 파이프라인 메트릭 응답(#126).
 *
 * @param produceRate  토픽 produce 속도(msg/sec). 현재 미수집 → 0.
 * @param consumeRate  consumer group consume 속도(msg/sec). 현재 미수집 → 0.
 * @param lagMessages  consumer group 전체 lag(메시지 수).
 * @param errorPct     커넥터 에러 비율(0~100). FAILED/PARTIALLY_FAILED 커넥터 기준.
 */
public record PipelineMetricsResponse(
        double produceRate,
        double consumeRate,
        long lagMessages,
        double errorPct
) {}
