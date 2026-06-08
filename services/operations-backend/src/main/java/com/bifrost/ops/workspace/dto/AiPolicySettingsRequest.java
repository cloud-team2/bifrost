package com.bifrost.ops.workspace.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record AiPolicySettingsRequest(
        @JsonAlias("aiAutonomous") Boolean autonomous,
        @JsonAlias("aiApprovalWaitMinutes") Integer approvalWaitMinutes,
        @JsonAlias("aiProdLock") Boolean prodLock
) {
}
