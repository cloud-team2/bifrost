package com.bifrost.ops.workspace.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record ThresholdSettingsRequest(
        @JsonAlias("lagWarningThreshold") Long warning,
        @JsonAlias("lagCriticalThreshold") Long critical
) {
}
