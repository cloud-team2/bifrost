package com.bifrost.ops.monitoring.slo;

/** Multi-window multi-burn-rate 규칙(#892). */
public record SloBurnRateRule(
        String name,
        int longWindowMinutes,
        int shortWindowMinutes,
        double burnRateThreshold,
        SloAlertRoute route) {
}
