package com.bifrost.ops.monitoring.slo;

import com.bifrost.ops.monitoring.sli.UserImpactSliType;

/** SLO burn-rate 평가 결과(#892). */
public record SloBurnRateEvaluation(
        UserImpactSliType sliType,
        SloAlertRoute route,
        String severity,
        String severityReason,
        String ruleName,
        Double longBurnRate,
        Double shortBurnRate,
        double targetRatio,
        int affectedResourceCount) {

    public boolean userImpactDetected() {
        return route == SloAlertRoute.PAGE || route == SloAlertRoute.TICKET;
    }
}
