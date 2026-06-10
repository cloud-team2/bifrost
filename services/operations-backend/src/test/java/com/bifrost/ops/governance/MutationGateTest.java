package com.bifrost.ops.governance;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.approval.ApprovalValidator;
import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.governance.changemanagement.ChangeTicketValidator;
import com.bifrost.ops.governance.evidence.EvidenceStore;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard;
import com.bifrost.ops.governance.policy.PolicyDecision;
import com.bifrost.ops.governance.policy.PolicyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MutationGateTest {

    private final PolicyGuard policyGuard = mock(PolicyGuard.class);
    private final ApprovalValidator approvalValidator = mock(ApprovalValidator.class);
    private final ChangeTicketValidator changeTicketValidator = mock(ChangeTicketValidator.class);
    private final EvidenceStore evidenceStore = mock(EvidenceStore.class);
    private final IdempotencyGuard idempotencyGuard = mock(IdempotencyGuard.class);
    private final AuditService auditService = mock(AuditService.class);
    private final MutationGate gate = new MutationGate(
            policyGuard,
            approvalValidator,
            changeTicketValidator,
            evidenceStore,
            idempotencyGuard,
            auditService,
            new ObjectMapper());

    @Test
    void executeCheckedDeniesPolicyBeforeMutation() {
        UUID tenantId = UUID.randomUUID();
        when(policyGuard.evaluate(tenantId, "shell_exec")).thenReturn(PolicyDecision.DENY);
        AtomicBoolean called = new AtomicBoolean(false);

        assertThatThrownBy(() -> gate.executeChecked(ctx(tenantId, "shell_exec", null, null), () -> {
                    called.set(true);
                    return "ok";
                }))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(called).isFalse();
        verifyNoInteractions(approvalValidator, changeTicketValidator, evidenceStore, auditService);
    }

    @Test
    void executeCheckedValidatesApprovalAndRecordsEvidenceAndAudit() {
        UUID tenantId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID beforeEvidenceId = UUID.randomUUID();
        UUID afterEvidenceId = UUID.randomUUID();
        when(policyGuard.evaluate(tenantId, "restart_connector")).thenReturn(PolicyDecision.REQUIRE_APPROVAL);
        when(approvalValidator.validateAndConsume(approvalId, tenantId, "restart_connector", "hash"))
                .thenReturn(approval(approvalId));
        when(evidenceStore.record(eq(tenantId), any(UUID.class), eq("BEFORE"), any()))
                .thenReturn(beforeEvidenceId);
        when(evidenceStore.record(eq(tenantId), any(UUID.class), eq("AFTER"), any()))
                .thenReturn(afterEvidenceId);

        String result = gate.executeChecked(
                new MutationContext(tenantId, "agent", "restart_connector", "CONNECTOR",
                        targetId, "idem-1", approvalId, null, "hash", Map.of("state", "before")),
                () -> "accepted");

        assertThat(result).isEqualTo("accepted");
        verify(approvalValidator).validateAndConsume(approvalId, tenantId, "restart_connector", "hash");
        verify(auditService).recordMutation(
                tenantId,
                "agent",
                "restart_connector",
                "CONNECTOR",
                targetId,
                "mutation executed via gate",
                PolicyDecision.REQUIRE_APPROVAL.name(),
                approvalId,
                afterEvidenceId);
    }

    @Test
    void executeCheckedValidatesChangeTicketForChangeManagementDecision() {
        UUID tenantId = UUID.randomUUID();
        UUID changeTicketId = UUID.randomUUID();
        when(policyGuard.evaluate(tenantId, "reset_offsets"))
                .thenReturn(PolicyDecision.REQUIRE_CHANGE_MANAGEMENT);
        when(evidenceStore.record(eq(tenantId), any(UUID.class), eq("AFTER"), any()))
                .thenReturn(UUID.randomUUID());

        String result = gate.executeChecked(
                ctx(tenantId, "reset_offsets", null, changeTicketId),
                () -> "accepted");

        assertThat(result).isEqualTo("accepted");
        verify(changeTicketValidator).validate(changeTicketId, tenantId, "reset_offsets");
        verifyNoInteractions(approvalValidator);
    }

    @Test
    void executeCheckedRequiresChangeTicketBeforeMutationForChangeManagementDecision() {
        UUID tenantId = UUID.randomUUID();
        when(policyGuard.evaluate(tenantId, "reset_offsets"))
                .thenReturn(PolicyDecision.REQUIRE_CHANGE_MANAGEMENT);
        AtomicBoolean called = new AtomicBoolean(false);

        assertThatThrownBy(() -> gate.executeChecked(ctx(tenantId, "reset_offsets", null, null), () -> {
                    called.set(true);
                    return "accepted";
                }))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.CHANGE_TICKET_REQUIRED));

        assertThat(called).isFalse();
        verifyNoInteractions(approvalValidator, changeTicketValidator, evidenceStore, auditService);
    }

    @Test
    void executeCheckedRecordsFailureEvidenceAndAuditBeforeRethrowing() {
        UUID tenantId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID beforeEvidenceId = UUID.randomUUID();
        UUID errorEvidenceId = UUID.randomUUID();
        when(policyGuard.evaluate(tenantId, "restart_connector")).thenReturn(PolicyDecision.REQUIRE_APPROVAL);
        when(approvalValidator.validateAndConsume(approvalId, tenantId, "restart_connector", "hash"))
                .thenReturn(approval(approvalId));
        when(evidenceStore.record(eq(tenantId), any(UUID.class), eq("BEFORE"), any()))
                .thenReturn(beforeEvidenceId);
        when(evidenceStore.record(eq(tenantId), any(UUID.class), eq("AFTER"), any()))
                .thenReturn(errorEvidenceId);

        RuntimeException failure = new RuntimeException("connect failed");
        assertThatThrownBy(() -> gate.executeChecked(
                new MutationContext(tenantId, "agent", "restart_connector", "CONNECTOR",
                        targetId, "idem-1", approvalId, null, "hash", Map.of("state", "before")),
                () -> {
                    throw failure;
                }))
                .isSameAs(failure);

        verify(auditService).recordMutation(
                tenantId,
                "agent",
                "restart_connector",
                "CONNECTOR",
                targetId,
                "mutation failed via gate",
                PolicyDecision.REQUIRE_APPROVAL.name(),
                approvalId,
                errorEvidenceId);
    }

    private static MutationContext ctx(UUID tenantId, String operation, UUID approvalId, UUID changeTicketId) {
        return new MutationContext(
                tenantId,
                "agent",
                operation,
                "CONNECTOR",
                UUID.randomUUID(),
                "idem-1",
                approvalId,
                changeTicketId,
                "hash",
                null);
    }

    private static ApprovalEntity approval(UUID approvalId) {
        ApprovalEntity approval = new ApprovalEntity();
        approval.setId(approvalId);
        approval.setTenantId(UUID.randomUUID());
        approval.setActor("operator");
        approval.setOperation("restart_connector");
        approval.setDecision("APPROVED");
        approval.setParamsHash("hash");
        approval.setExpiresAt(Instant.now().plusSeconds(60));
        return approval;
    }
}
