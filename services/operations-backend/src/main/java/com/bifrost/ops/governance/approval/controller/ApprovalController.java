package com.bifrost.ops.governance.approval.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.approval.ApprovalValidator;
import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.approval.persistence.repository.ApprovalRepository;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Spring-owned approval record HTTP facade for internal ops callers.
 *
 * <p>The controller only exposes the existing approval repository and validator. Execution-time
 * validation remains delegated to {@link ApprovalValidator}, which owns params_hash, expiry, and
 * single-use consumption checks.
 */
@RestController
@Validated
@RequestMapping("/internal/ops/approvals")
public class ApprovalController {

    private static final String DECISION_PENDING = "PENDING";
    private static final int MAX_LIST_LIMIT = 500;

    private final ApprovalRepository approvalRepository;
    private final ApprovalValidator approvalValidator;
    private final AuditService auditService;

    public ApprovalController(ApprovalRepository approvalRepository,
                              ApprovalValidator approvalValidator,
                              AuditService auditService) {
        this.approvalRepository = approvalRepository;
        this.approvalValidator = approvalValidator;
        this.auditService = auditService;
    }

    /** create_approval — create a pending approval record from FastAPI policy output. */
    @PostMapping
    @Transactional
    public ResponseEntity<OpsEnvelope<ApprovalCreatedResult>> createApproval(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateApprovalRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);

        ApprovalEntity approval = new ApprovalEntity();
        approval.setId(UUID.randomUUID());
        approval.setTenantId(request.tenantId());
        approval.setActor(request.requiredApprover().toString());
        approval.setOperation(request.toolName());
        approval.setParamsHash(request.paramsHash());
        approval.setDecision(DECISION_PENDING);
        approval.setExpiresAt(Instant.now().plus(request.expiresInMinutes(), ChronoUnit.MINUTES));

        ApprovalEntity saved = approvalRepository.save(approval);
        auditService.record(
                saved.getTenantId(),
                AuditService.ACTOR_SYSTEM,
                "create_approval",
                "approval",
                saved.getId(),
                "operation=" + saved.getOperation());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OpsEnvelope.ok(requestId, "create_approval", ApprovalCreatedResult.from(saved)));
    }

    /**
     * agent_preapprove — ai-service가 자체 HITL 게이트를 통과한 뒤 Spring MutationGate용
     * approval을 직접 생성+승인 처리한다. ai-service가 사용자 JWT 없이 내부에서 호출하며,
     * internal.ops.token 설정 시 X-Internal-Token service identity가 필요하다.
     */
    @PostMapping("/preapproved")
    @Transactional
    public ResponseEntity<OpsEnvelope<ApprovalCreatedResult>> createPreapprovedApproval(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateApprovalRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);

        ApprovalEntity approval = new ApprovalEntity();
        approval.setId(UUID.randomUUID());
        approval.setTenantId(request.tenantId());
        approval.setActor(AuditService.ACTOR_SYSTEM);
        approval.setOperation(request.toolName());
        approval.setParamsHash(request.paramsHash());
        approval.setDecision("APPROVED");
        approval.setExpiresAt(Instant.now().plus(request.expiresInMinutes(), ChronoUnit.MINUTES));

        ApprovalEntity saved = approvalRepository.save(approval);
        auditService.record(
                saved.getTenantId(),
                AuditService.ACTOR_SYSTEM,
                "agent_preapprove",
                "approval",
                saved.getId(),
                "operation=" + saved.getOperation() + " (ai-service HITL pre-approved)");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OpsEnvelope.ok(requestId, "agent_preapprove", ApprovalCreatedResult.from(saved)));
    }

    /** decide_approval — record a human approve/reject decision. */
    @PostMapping("/{approvalId}/decision")
    @Transactional
    public ResponseEntity<OpsEnvelope<ApprovalResult>> decideApproval(
            @PathVariable UUID approvalId,
            HttpServletRequest servletRequest,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);
        AuthenticatedUser actor = authenticatedActor();
        requireRequestActor(actor, request.tenantId(), request.decidedBy());
        ApprovalEntity approval = requireApproval(approvalId, request.tenantId());

        requireTrustedApprover(approval, actor);
        if (!DECISION_PENDING.equals(approval.getDecision())) {
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED, "approval already decided: " + approvalId);
        }
        if (approval.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.APPROVAL_EXPIRED, "approval expired: " + approvalId);
        }

        String decision = request.decision().toUpperCase(Locale.ROOT);
        approval.setDecision(decision);
        ApprovalEntity saved = approvalRepository.save(approval);
        auditService.record(
                saved.getTenantId(),
                actor.userId().toString(),
                "approval_decision",
                "approval",
                saved.getId(),
                decisionDetail(decision, request.comment()));

        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "decide_approval", ApprovalResult.from(saved)));
    }

    /** validate_approval — delegate execution-time validation and single-use consumption. */
    @PostMapping("/{approvalId}/validate")
    @Transactional
    public ResponseEntity<OpsEnvelope<ApprovalValidationResult>> validateForExecution(
            @PathVariable UUID approvalId,
            HttpServletRequest servletRequest,
            @Valid @RequestBody ApprovalValidateRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);
        requireApproval(approvalId, request.tenantId());
        ApprovalEntity approval = approvalValidator.validateAndConsume(approvalId, request.paramsHash());
        auditService.record(
                approval.getTenantId(),
                AuditService.ACTOR_SYSTEM,
                "approval_validate",
                "approval",
                approval.getId(),
                "approval validated for execution");
        return ResponseEntity.ok(OpsEnvelope.ok(
                requestId,
                "validate_approval",
                ApprovalValidationResult.from(approval)));
    }

    /** get_approval — fetch a single approval record. */
    @GetMapping("/{approvalId}")
    public ResponseEntity<OpsEnvelope<ApprovalResult>> getApproval(
            @PathVariable UUID approvalId,
            @RequestParam UUID tenantId,
            HttpServletRequest servletRequest) {
        String requestId = AgentHeaders.requestId(servletRequest);
        ApprovalEntity approval = requireApproval(approvalId, tenantId);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_approval", ApprovalResult.from(approval)));
    }

    /** list_approvals — simple pending-list facade for internal UI/FastAPI callers. */
    @GetMapping
    public ResponseEntity<OpsEnvelope<List<ApprovalResult>>> listApprovals(
            @RequestParam UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String actorId,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest servletRequest) {
        String requestId = AgentHeaders.requestId(servletRequest);
        validateLimit(limit);
        List<ApprovalResult> approvals = findApprovals(tenantId, status, actorId, limit).stream()
                .map(ApprovalResult::from)
                .toList();
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "list_approvals", approvals));
    }

    private ApprovalEntity requireApproval(UUID approvalId, UUID tenantId) {
        return approvalRepository.findByIdAndTenantId(approvalId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.APPROVAL_NOT_FOUND,
                        "approval not found: " + approvalId));
    }

    private List<ApprovalEntity> findApprovals(UUID tenantId, String status, String actorId, int limit) {
        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        String effectiveStatus = status == null || status.isBlank() ? DECISION_PENDING : status;
        if (actorId != null && !actorId.isBlank()) {
            return approvalRepository.findByTenantIdAndDecisionIgnoreCaseAndActor(
                    tenantId, effectiveStatus, actorId, page);
        }
        return approvalRepository.findByTenantIdAndDecisionIgnoreCase(tenantId, effectiveStatus, page);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_LIST_LIMIT);
        }
    }

    private static void requireRequestActor(AuthenticatedUser actor, UUID tenantId, UUID decidedBy) {
        if (!actor.tenantId().equals(tenantId) || !actor.userId().equals(decidedBy)) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "approval actor scope mismatch");
        }
    }

    private static void requireTrustedApprover(ApprovalEntity approval, AuthenticatedUser actor) {
        if (!approval.getActor().equals(actor.userId().toString())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH,
                    "approval approver mismatch: " + approval.getId());
        }
    }

    private static AuthenticatedUser authenticatedActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "approval decision requires authenticated actor");
        }
        return user;
    }

    private static String decisionDetail(String decision, String comment) {
        if (comment == null || comment.isBlank()) {
            return "decision=" + decision;
        }
        return "decision=" + decision + "; comment=" + comment.replaceAll("[\\r\\n]", " ");
    }

    private static void rejectUnknown(String name) {
        throw new IllegalArgumentException("unsupported field: " + name);
    }

    public record CreateApprovalRequest(
            @NotNull UUID tenantId,
            @NotBlank @Size(max = 100) String toolName,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String paramsHash,
            @NotNull UUID requiredApprover,
            @NotNull @Positive @Max(1440) Integer expiresInMinutes
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            rejectUnknown(name);
        }
    }

    public record ApprovalDecisionRequest(
            @NotBlank @Pattern(regexp = "(?i)approved|rejected") String decision,
            @NotNull UUID tenantId,
            @NotNull UUID decidedBy,
            @Size(max = 500) String comment
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            rejectUnknown(name);
        }
    }

    public record ApprovalValidateRequest(
            @NotNull UUID tenantId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String paramsHash
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            rejectUnknown(name);
        }
    }

    public record ApprovalCreatedResult(
            UUID approvalId,
            UUID tenantId,
            String actor,
            String operation,
            String status,
            Instant expiresAt,
            Instant createdAt,
            String paramsHash
    ) {
        static ApprovalCreatedResult from(ApprovalEntity approval) {
            return new ApprovalCreatedResult(
                    approval.getId(),
                    approval.getTenantId(),
                    approval.getActor(),
                    approval.getOperation(),
                    approval.getDecision().toLowerCase(Locale.ROOT),
                    approval.getExpiresAt(),
                    approval.getCreatedAt(),
                    approval.getParamsHash());
        }
    }

    public record ApprovalResult(
            UUID approvalId,
            UUID tenantId,
            String actor,
            String operation,
            String status,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt
    ) {
        static ApprovalResult from(ApprovalEntity approval) {
            return new ApprovalResult(
                    approval.getId(),
                    approval.getTenantId(),
                    approval.getActor(),
                    approval.getOperation(),
                    approval.getDecision().toLowerCase(Locale.ROOT),
                    approval.getExpiresAt(),
                    approval.getUsedAt(),
                    approval.getCreatedAt());
        }
    }

    public record ApprovalValidationResult(
            UUID approvalId,
            String status,
            Instant usedAt
    ) {
        static ApprovalValidationResult from(ApprovalEntity approval) {
            return new ApprovalValidationResult(
                    approval.getId(),
                    "validated",
                    approval.getUsedAt());
        }
    }
}
