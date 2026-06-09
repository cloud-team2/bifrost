package com.bifrost.ops.internalops.dto;

import java.util.List;

/** Agent list_alerts result envelope body. */
public record AlertListResult(
        List<AlertSummaryResult> alerts,
        String summary
) {
    public static AlertListResult of(List<AlertSummaryResult> alerts) {
        return new AlertListResult(alerts, alerts.size() + " alerts matched");
    }
}
