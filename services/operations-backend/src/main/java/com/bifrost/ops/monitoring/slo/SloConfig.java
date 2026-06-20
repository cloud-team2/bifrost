package com.bifrost.ops.monitoring.slo;

import com.bifrost.ops.monitoring.sli.UserImpactSliType;

import java.util.List;

/** SLO target과 burn-rate 규칙 구성(#892). */
public record SloConfig(
        UserImpactSliType sliType,
        double targetRatio,
        List<SloBurnRateRule> rules) {

    public static SloConfig defaultFor(UserImpactSliType type) {
        return new SloConfig(type, type.targetRatio(), List.of(
                new SloBurnRateRule("page_fast_burn", 60, 5, 14.4, SloAlertRoute.PAGE),
                new SloBurnRateRule("page_sustained_burn", 360, 30, 6.0, SloAlertRoute.PAGE),
                new SloBurnRateRule("ticket_slow_burn", 4_320, 360, 1.0, SloAlertRoute.TICKET)
        ));
    }
}
