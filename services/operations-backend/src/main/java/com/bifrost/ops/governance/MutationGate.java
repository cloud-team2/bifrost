package com.bifrost.ops.governance;

import com.bifrost.ops.governance.approval.ApprovalValidator;
import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.governance.changemanagement.ChangeTicketValidator;
import com.bifrost.ops.governance.evidence.EvidenceStore;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard;
import com.bifrost.ops.governance.policy.PolicyDecision;
import com.bifrost.ops.governance.policy.PolicyGuard;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * mutation 안전 게이트 오케스트레이터(S3).
 * §7 파이프라인: idempotency → policy → approval → before evidence → execute → after evidence → audit.
 */
@Service
public class MutationGate {

    private static final Logger log = LoggerFactory.getLogger(MutationGate.class);

    private final PolicyGuard policyGuard;
    private final ApprovalValidator approvalValidator;
    private final ChangeTicketValidator changeTicketValidator;
    private final EvidenceStore evidenceStore;
    private final IdempotencyGuard idempotencyGuard;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public MutationGate(PolicyGuard policyGuard,
                        ApprovalValidator approvalValidator,
                        ChangeTicketValidator changeTicketValidator,
                        EvidenceStore evidenceStore,
                        IdempotencyGuard idempotencyGuard,
                        AuditService auditService,
                        ObjectMapper objectMapper) {
        this.policyGuard = policyGuard;
        this.approvalValidator = approvalValidator;
        this.changeTicketValidator = changeTicketValidator;
        this.evidenceStore = evidenceStore;
        this.idempotencyGuard = idempotencyGuard;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * mutation을 게이트 파이프라인을 거쳐 실행한다.
     * @param ctx      mutation 컨텍스트
     * @param mutation 실제 mutation 로직 (게이트 통과 후 실행)
     */
    @Transactional
    public <T> GateResult<T> execute(MutationContext ctx, Supplier<T> mutation) {
        // [1] idempotency
        if (ctx.idempotencyKey() != null) {
            Optional<String> cached = idempotencyGuard.check(ctx.idempotencyKey(), ctx.tenantId());
            if (cached.isPresent()) {
                log.debug("[gate] idempotent duplicate: key={}", ctx.idempotencyKey());
                return GateResult.duplicate(null);
            }
        }

        T result;
        try {
            result = executeChecked(ctx, mutation);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.APPROVAL_NOT_FOUND) {
                return GateResult.requireApproval(e.getMessage());
            }
            return GateResult.blocked(e.getMessage());
        }

        // [8] idempotency complete
        if (ctx.idempotencyKey() != null) {
            idempotencyGuard.complete(ctx.idempotencyKey(), ctx.tenantId(), toJson(result));
        }

        return GateResult.ok(result);
    }

    /**
     * Executes one already-reserved mutation through the governance gates.
     *
     * <p>This variant does not own idempotency replay because internal ops controllers need to
     * preserve HTTP status and cached envelope semantics in {@link IdempotencyGuard}.
     */
    public <T> T executeChecked(MutationContext ctx, Supplier<T> mutation) {
        GovernanceDecision governance = validateGovernance(ctx);
        UUID mutationId = UUID.randomUUID();
        UUID beforeEvidenceId = null;
        if (ctx.beforeSnapshot() != null) {
            beforeEvidenceId = evidenceStore.record(ctx.tenantId(), mutationId, "BEFORE", ctx.beforeSnapshot());
        }

        try {
            T result = mutation.get();
            UUID afterEvidenceId = evidenceStore.record(ctx.tenantId(), mutationId, "AFTER",
                    Map.of("operation", ctx.operation(), "result", String.valueOf(result)));
            auditService.recordMutation(
                    ctx.tenantId(), ctx.actor(), ctx.operation(),
                    ctx.targetType(), ctx.targetId(),
                    "mutation executed via gate",
                    governance.decision().name(), governance.approvalId(),
                    afterEvidenceId != null ? afterEvidenceId : beforeEvidenceId);
            return result;
        } catch (RuntimeException e) {
            UUID errorEvidenceId = evidenceStore.record(ctx.tenantId(), mutationId, "AFTER",
                    Map.of("operation", ctx.operation(), "error", safeMessage(e)));
            auditService.recordMutation(
                    ctx.tenantId(), ctx.actor(), ctx.operation(),
                    ctx.targetType(), ctx.targetId(),
                    "mutation failed via gate",
                    governance.decision().name(), governance.approvalId(),
                    errorEvidenceId != null ? errorEvidenceId : beforeEvidenceId);
            log.warn("[gate] mutation failed: op={} cause={}", ctx.operation(), safeMessage(e));
            throw e;
        }
    }

    private GovernanceDecision validateGovernance(MutationContext ctx) {
        PolicyDecision decision = policyGuard.evaluate(ctx.tenantId(), ctx.operation());
        if (decision == PolicyDecision.DENY) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "operation '" + ctx.operation() + "' denied by policy");
        }
        UUID approvalId = null;
        if (decision == PolicyDecision.REQUIRE_APPROVAL) {
            if (ctx.approvalId() == null) {
                throw new ApiException(ErrorCode.APPROVAL_NOT_FOUND,
                        "operation '" + ctx.operation() + "' requires approval");
            }
            ApprovalEntity approval = approvalValidator.validateAndConsume(
                    ctx.approvalId(), ctx.tenantId(), ctx.operation(), ctx.paramsHash());
            approvalId = approval.getId();
        }
        if (decision == PolicyDecision.REQUIRE_CHANGE_MANAGEMENT) {
            if (ctx.changeTicketId() == null) {
                throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                        "operation '" + ctx.operation() + "' requires change ticket");
            }
            changeTicketValidator.validate(ctx.changeTicketId(), ctx.tenantId(), ctx.operation());
        }
        return new GovernanceDecision(decision, approvalId);
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private record GovernanceDecision(PolicyDecision decision, UUID approvalId) {}
}
