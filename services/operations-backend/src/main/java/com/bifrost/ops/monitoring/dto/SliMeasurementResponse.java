package com.bifrost.ops.monitoring.dto;

import com.bifrost.ops.monitoring.sli.UserImpactSliStatus;
import com.bifrost.ops.monitoring.sli.UserImpactSliType;

import java.time.Instant;

/** SLI 산출 결과 응답(#891). */
public record SliMeasurementResponse(
        UserImpactSliType type,
        String apiName,
        String displayName,
        String goodEvent,
        String totalEvent,
        double goodEvents,
        double totalEvents,
        Double sliRatio,
        UserImpactSliStatus status,
        double targetRatio,
        int windowMinutes,
        Instant measuredAt,
        String source,
        String note) {

    public static SliMeasurementResponse of(UserImpactSliType type,
                                            double goodEvents,
                                            double totalEvents,
                                            Double sliRatio,
                                            UserImpactSliStatus status,
                                            int windowMinutes,
                                            Instant measuredAt,
                                            String source,
                                            String note) {
        return new SliMeasurementResponse(
                type,
                type.apiName(),
                type.displayName(),
                type.goodEvent(),
                type.totalEvent(),
                goodEvents,
                totalEvents,
                sliRatio,
                status,
                type.targetRatio(),
                windowMinutes,
                measuredAt,
                source,
                note);
    }
}
