package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;

public record AiPolicySettingsResponse(
        boolean autonomous,
        int approvalWaitMinutes,
        boolean prodLock
) {
    public static AiPolicySettingsResponse from(WorkspaceSettingsEntity settings) {
        return new AiPolicySettingsResponse(
                settings.isAiAutonomous(),
                settings.getAiApprovalWaitMinutes(),
                settings.isAiProdLock());
    }
}
