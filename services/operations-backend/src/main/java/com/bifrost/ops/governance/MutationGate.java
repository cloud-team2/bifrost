package com.bifrost.ops.governance;

import com.bifrost.ops.governance.approval.ApprovalValidator;
import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.governance.evidence.EvidenceStore;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard;
import com.bifrost.ops.governance.policy.PolicyDecision;
import com.bifrost.ops.governance.policy.PolicyGuard;
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
    private final EvidenceStore evidenceStore;
    private final IdempotencyGuard idempotencyGuard;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public MutationGate(PolicyGuard policyGuard,
                        ApprovalValidator approvalValidator,
                        EvidenceStore evidenceStore,
                        IdempotencyGuard idempotencyGuard,
                        AuditService auditService,
                        ObjectMapper objectMapper) {
        this.policyGuard = policyGuard;
        this.approvalValidator = approvalValidator;
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

        // [2] policy evaluation
        PolicyDecision decision = policyGuard.evaluate(ctx.tenantId(), ctx.operation());

        // [3] approval validation (REQUIRE_APPROVAL)
        UUID approvalId = null;
        if (decision == PolicyDecision.REQUIRE_APPROVAL) {
            if (ctx.approvalId() == null) {
                return GateResult.requireApproval("operation '" + ctx.operation() + "' requires approval");
            }
            try {
                ApprovalEntity approval = approvalValidator.validateAndConsume(ctx.approvalId(), ctx.paramsHash());
                approvalId = approval.getId();
            } catch (Exception e) {
                return GateResult.blocked("approval validation failed: " + e.getMessage());
            }
        } else if (decision == PolicyDecision.DENY) {
            return GateResult.blocked("operation '" + ctx.operation() + "' denied by policy");
        }

        // [4] before evidence
        UUID mutationId = UUID.randomUUID();
        UUID beforeEvidenceId = null;
        if (ctx.beforeSnapshot() != null) {
            beforeEvidenceId = evidenceStore.record(ctx.tenantId(), mutationId, "BEFORE", ctx.beforeSnapshot());
        }

        // [5] execute
        T result;
        try {
            result = mutation.get();
        } catch (Exception e) {
            log.warn("[gate] mutation failed: op={} cause={}", ctx.operation(), e.getMessage());
            throw e;
        }

        // [6] after evidence
        UUID afterEvidenceId = evidenceStore.record(ctx.tenantId(), mutationId, "AFTER",
                Map.of("operation", ctx.operation(), "result", String.valueOf(result)));

        // [7] audit
        auditService.recordMutation(
                ctx.tenantId(), ctx.actor(), ctx.operation(),
                ctx.targetType(), ctx.targetId(),
                "mutation executed via gate",
                decision.name(), approvalId,
                afterEvidenceId != null ? afterEvidenceId : beforeEvidenceId);

        // [8] idempotency complete
        if (ctx.idempotencyKey() != null) {
            idempotencyGuard.complete(ctx.idempotencyKey(), ctx.tenantId(), toJson(result));
        }

        return GateResult.ok(result);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
