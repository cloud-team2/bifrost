package com.bifrost.ops.governance.approval;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.approval.persistence.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalValidatorTest {

    private static final String PARAMS_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final ApprovalRepository repository = mock(ApprovalRepository.class);
    private final ApprovalValidator validator = new ApprovalValidator(repository);

    @Test
    void consumesApprovedUnusedApproval() {
        UUID approvalId = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "APPROVED", Instant.now().plusSeconds(60), null, PARAMS_HASH);
        when(repository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(repository.save(any(ApprovalEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalEntity consumed = validator.validateAndConsume(approvalId, PARAMS_HASH);

        assertThat(consumed.getUsedAt()).isNotNull();
        verify(repository).save(approval);
    }

    @Test
    void missingApprovalUsesApprovalNotFoundCode() {
        UUID approvalId = UUID.randomUUID();
        when(repository.findById(approvalId)).thenReturn(Optional.empty());

        assertApiCode(() -> validator.validateAndConsume(approvalId, PARAMS_HASH), ErrorCode.APPROVAL_NOT_FOUND);
    }

    @Test
    void expiredApprovalUsesExpiredCode() {
        UUID approvalId = UUID.randomUUID();
        when(repository.findById(approvalId)).thenReturn(Optional.of(
                approval(approvalId, "APPROVED", Instant.now().minusSeconds(1), null, PARAMS_HASH)));

        assertApiCode(() -> validator.validateAndConsume(approvalId, PARAMS_HASH), ErrorCode.APPROVAL_EXPIRED);
    }

    @Test
    void pendingApprovalUsesScopeMismatchCodeWithoutSaving() {
        UUID approvalId = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(60), null, PARAMS_HASH);
        when(repository.findById(approvalId)).thenReturn(Optional.of(approval));

        assertApiCode(() -> validator.validateAndConsume(approvalId, PARAMS_HASH), ErrorCode.APPROVAL_SCOPE_MISMATCH);

        verify(repository, never()).save(any());
    }

    @Test
    void alreadyUsedApprovalUsesAlreadyUsedCode() {
        UUID approvalId = UUID.randomUUID();
        when(repository.findById(approvalId)).thenReturn(Optional.of(
                approval(approvalId, "APPROVED", Instant.now().plusSeconds(60), Instant.now(), PARAMS_HASH)));

        assertApiCode(() -> validator.validateAndConsume(approvalId, PARAMS_HASH), ErrorCode.APPROVAL_ALREADY_USED);
    }

    @Test
    void paramsMismatchUsesScopeMismatchCode() {
        UUID approvalId = UUID.randomUUID();
        when(repository.findById(approvalId)).thenReturn(Optional.of(
                approval(approvalId, "APPROVED", Instant.now().plusSeconds(60), null, PARAMS_HASH)));

        assertApiCode(() -> validator.validateAndConsume(approvalId,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                ErrorCode.APPROVAL_SCOPE_MISMATCH);
    }

    private static ApprovalEntity approval(UUID id, String decision, Instant expiresAt, Instant usedAt, String paramsHash) {
        ApprovalEntity approval = new ApprovalEntity();
        approval.setId(id);
        approval.setTenantId(UUID.randomUUID());
        approval.setActor("project_operator");
        approval.setOperation("restart_connector_task");
        approval.setParamsHash(paramsHash);
        approval.setDecision(decision);
        approval.setExpiresAt(expiresAt);
        approval.setUsedAt(usedAt);
        return approval;
    }

    private static void assertApiCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(expected));
    }
}
