package com.bifrost.ops.monitoring.dto;

import com.bifrost.ops.monitoring.sli.UserImpactSliType;

/** SLI의 good_event/total_event 정의 응답(#891). */
public record SliDefinitionResponse(
        UserImpactSliType type,
        String apiName,
        String displayName,
        String description,
        String goodEvent,
        String totalEvent,
        String unit,
        double targetRatio) {

    public static SliDefinitionResponse from(UserImpactSliType type) {
        return new SliDefinitionResponse(
                type,
                type.apiName(),
                type.displayName(),
                type.description(),
                type.goodEvent(),
                type.totalEvent(),
                type.unit(),
                type.targetRatio());
    }
}
