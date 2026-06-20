package com.bifrost.ops.monitoring.sli;

/** SLI 측정값의 현재 판정 상태(#891). */
public enum UserImpactSliStatus {
    GOOD,
    WARNING,
    CRITICAL,
    UNKNOWN
}
