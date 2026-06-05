package com.bifrost.ops.pipeline.dto;

/**
 * 단일 값 시계열 한 점(#126). 소스 지연(ms)·미동기화 row 추이 등에 공용.
 *
 * @param timestamp epoch millis
 * @param value     해당 시점 값
 */
public record MetricPoint(long timestamp, double value) {}
