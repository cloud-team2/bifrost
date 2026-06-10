package com.bifrost.ops.governance.policy;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyGuardTest {

    private final WorkspaceSettingsRepository settingsRepository = mock(WorkspaceSettingsRepository.class);
    private final PolicyGuard guard = new PolicyGuard(settingsRepository);

    @Test
    void unregisteredOperationIsDenied() {
        assertThat(guard.evaluate(UUID.randomUUID(), "shell_exec")).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void highRiskOperationUsesExistingProdLockSetting() {
        UUID tenantId = UUID.randomUUID();
        WorkspaceSettingsEntity settings = WorkspaceSettingsEntity.defaults(tenantId);
        settings.setAiProdLock(false);
        when(settingsRepository.findById(tenantId)).thenReturn(Optional.of(settings));

        assertThat(guard.evaluate(tenantId, "restart_connector")).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void highRiskOperationDefaultsToApprovalWhenSettingsAreMissing() {
        UUID tenantId = UUID.randomUUID();
        when(settingsRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThat(guard.evaluate(tenantId, "restart_consumer_group"))
                .isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
    }

    @ParameterizedTest
    @CsvSource({
            "pause_connector,ALLOW",
            "resume_connector,ALLOW",
            "scale_workers,ALLOW",
            "update_connector,REQUIRE_CHANGE_MANAGEMENT",
            "reset_offsets,REQUIRE_CHANGE_MANAGEMENT",
            "delete_connector,DENY"
    })
    void registeredOperationsUseExplicitPolicyDecision(String operation, PolicyDecision decision) {
        assertThat(guard.evaluate(UUID.randomUUID(), operation)).isEqualTo(decision);
    }
}
