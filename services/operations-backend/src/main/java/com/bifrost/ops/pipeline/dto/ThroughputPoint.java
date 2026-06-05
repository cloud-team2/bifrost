package com.bifrost.ops.pipeline.dto;

/**
 * 처리량 추이 시계열 한 점(#126, Overview 처리량 차트).
 *
 * @param timestamp epoch millis
 * @param produceRate 해당 시점 produce rate(msg/sec)
 * @param consumeRate 해당 시점 consume rate(msg/sec)
 */
public record ThroughputPoint(long timestamp, double produceRate, double consumeRate) {}
