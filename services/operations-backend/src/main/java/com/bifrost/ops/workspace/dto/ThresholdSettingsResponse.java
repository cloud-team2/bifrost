package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;

public record ThresholdSettingsResponse(long warning, long critical) {
    public static ThresholdSettingsResponse from(WorkspaceSettingsEntity settings) {
        return new ThresholdSettingsResponse(
                settings.getLagWarningThreshold(),
                settings.getLagCriticalThreshold());
    }
}
