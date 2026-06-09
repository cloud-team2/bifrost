package com.bifrost.ops.governance.policy;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * mutation 위험 등급을 평가해 policy 결정을 반환한다(S3).
 * HIGH 등급 + ai_prod_lock=true → REQUIRE_APPROVAL.
 */
@Service
public class PolicyGuard {

    private static final Map<String, RiskLevel> RISK_MAP = Map.of(
            "restart_connector",  RiskLevel.HIGH,
            "delete_connector",   RiskLevel.HIGH,
            "update_connector",   RiskLevel.MEDIUM,
            "pause_connector",    RiskLevel.MEDIUM,
            "resume_connector",   RiskLevel.LOW,
            "reset_offsets",      RiskLevel.HIGH,
            "scale_workers",      RiskLevel.MEDIUM
    );

    private final WorkspaceSettingsRepository settingsRepository;

    public PolicyGuard(WorkspaceSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public PolicyDecision evaluate(UUID tenantId, String operation) {
        RiskLevel risk = RISK_MAP.getOrDefault(operation, RiskLevel.MEDIUM);
        if (risk == RiskLevel.HIGH) {
            WorkspaceSettingsEntity settings = settingsRepository.findById(tenantId)
                    .orElse(WorkspaceSettingsEntity.defaults(tenantId));
            if (settings.isAiProdLock()) return PolicyDecision.REQUIRE_APPROVAL;
        }
        if (risk == RiskLevel.LOW) return PolicyDecision.ALLOW;
        return PolicyDecision.ALLOW; // MEDIUM: 승인 없이 실행, 단 audit 기록
    }

    public RiskLevel riskOf(String operation) {
        return RISK_MAP.getOrDefault(operation, RiskLevel.MEDIUM);
    }
}
