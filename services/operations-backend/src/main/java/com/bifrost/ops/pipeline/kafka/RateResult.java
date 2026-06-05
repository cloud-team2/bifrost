package com.bifrost.ops.pipeline.kafka;

/** 두 스냅샷 간 delta로 계산한 rate 결과. */
public record RateResult(double produceRate, double consumeRate, long lagMessages) {
    public static final RateResult EMPTY = new RateResult(0.0, 0.0, 0L);
}
