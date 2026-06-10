package com.bifrost.ops.governance.policy;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * mutation 위험 등급을 평가해 policy 결정을 반환한다(S3).
 * 등록되지 않은 operation은 안전하게 DENY한다.
 */
@Service
public class PolicyGuard {

    private static final Map<String, RiskLevel> RISK_MAP = Map.of(
            "restart_connector",  RiskLevel.HIGH,
            "delete_connector",   RiskLevel.HIGH,
            "update_connector",   RiskLevel.MEDIUM,
            "pause_connector",    RiskLevel.MEDIUM,
            "resume_connector",   RiskLevel.LOW,
            "restart_consumer_group", RiskLevel.HIGH,
            "reset_offsets",      RiskLevel.HIGH,
            "scale_workers",      RiskLevel.MEDIUM
    );

    private static final Set<String> CHANGE_MANAGEMENT_OPS = Set.of("update_connector", "reset_offsets");
    private static final Set<String> DENIED_OPS = Set.of("delete_connector");

    private final WorkspaceSettingsRepository settingsRepository;

    public PolicyGuard(WorkspaceSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public PolicyDecision evaluate(UUID tenantId, String operation) {
        RiskLevel risk = RISK_MAP.get(operation);
        if (risk == null) {
            return PolicyDecision.DENY;
        }
        if (DENIED_OPS.contains(operation)) {
            return PolicyDecision.DENY;
        }
        if (CHANGE_MANAGEMENT_OPS.contains(operation)) {
            return PolicyDecision.REQUIRE_CHANGE_MANAGEMENT;
        }
        if (risk == RiskLevel.HIGH) {
            WorkspaceSettingsEntity settings = settingsRepository.findById(tenantId)
                    .orElse(WorkspaceSettingsEntity.defaults(tenantId));
            if (settings.isAiProdLock()) {
                return PolicyDecision.REQUIRE_APPROVAL;
            }
        }
        return PolicyDecision.ALLOW;
    }

    public Optional<RiskLevel> riskOf(String operation) {
        return Optional.ofNullable(RISK_MAP.get(operation));
    }
}
