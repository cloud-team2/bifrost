package com.bifrost.ops.governance.changemanagement.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.governance.changemanagement.ChangeTicketValidator;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Spring-owned change ticket HTTP facade for internal ops callers.
 *
 * <p>Execution-time validation is delegated to {@link ChangeTicketValidator}; this controller does
 * not duplicate ticket validation rules beyond request binding and existing entity exposure.
 */
@RestController
@RequestMapping("/internal/ops/change-tickets")
public class ChangeTicketController {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_APPROVED = "APPROVED";

    private final ChangeTicketRepository changeTicketRepository;
    private final ChangeTicketValidator changeTicketValidator;
    private final AuditService auditService;

    public ChangeTicketController(ChangeTicketRepository changeTicketRepository,
                                  ChangeTicketValidator changeTicketValidator,
                                  AuditService auditService) {
        this.changeTicketRepository = changeTicketRepository;
        this.changeTicketValidator = changeTicketValidator;
        this.auditService = auditService;
    }

    /** create_change_ticket — create a Spring-owned change ticket record. */
    @PostMapping
    @Transactional
    public ResponseEntity<OpsEnvelope<ChangeTicketResult>> createChangeTicket(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateChangeTicketRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);
        AuthenticatedUser actor = authenticatedActor();
        requireTenant(actor, request.tenantId());
        requireDifferentApprover(actor, request.requiredApprover());

        ChangeTicketEntity ticket = new ChangeTicketEntity();
        ticket.setId(UUID.randomUUID());
        ticket.setTenantId(request.tenantId());
        ticket.setTitle(request.title());
        ticket.setStatus(STATUS_OPEN);
        ticket.setWindowStart(request.windowStart());
        ticket.setWindowEnd(request.windowEnd());
        ticket.setRollbackPlan(request.rollbackPlan());
        ticket.setImpactAnalysis(request.impactAnalysis());
        ticket.setScopeOperation(request.scopeOperation());
        ticket.setRequiredApprover(request.requiredApprover());
        ticket.setRequestedBy(actor.userId());
        requireCompleteTicket(ticket);
        validateWindowOrder(ticket.getWindowStart(), ticket.getWindowEnd());

        ChangeTicketEntity saved = changeTicketRepository.save(ticket);
        auditService.record(
                saved.getTenantId(),
                actor.userId().toString(),
                "create_change_ticket",
                "change_ticket",
                saved.getId(),
                "title=" + saved.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OpsEnvelope.ok(requestId, "create_change_ticket", ChangeTicketResult.from(saved)));
    }

    /** approve_change_ticket — transition a complete OPEN ticket to APPROVED. */
    @PostMapping("/{changeTicketId}/approve")
    @Transactional
    public ResponseEntity<OpsEnvelope<ChangeTicketResult>> approveChangeTicket(
            @PathVariable UUID changeTicketId,
            HttpServletRequest servletRequest,
            @Valid @RequestBody ChangeTicketApprovalRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);
        AuthenticatedUser actor = authenticatedActor();
        requireRequestActor(actor, request.tenantId(), request.approvedBy());
        ChangeTicketEntity ticket = requireChangeTicketForUpdate(changeTicketId, request.tenantId());
        requireTrustedApprover(ticket, actor);
        if (!STATUS_OPEN.equals(ticket.getStatus())) {
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED,
                    "change ticket already decided: " + changeTicketId);
        }
        requireCompleteTicket(ticket);
        validateWindowOrder(ticket.getWindowStart(), ticket.getWindowEnd());

        ticket.setStatus(STATUS_APPROVED);
        ticket.setApprovedBy(actor.userId());
        ticket.setApprovedAt(Instant.now());
        ChangeTicketEntity saved = changeTicketRepository.save(ticket);
        auditService.record(
                saved.getTenantId(),
                actor.userId().toString(),
                "change_ticket_approve",
                "change_ticket",
                saved.getId(),
                approvalDetail(request.comment()));

        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "approve_change_ticket", ChangeTicketResult.from(saved)));
    }

    /** validate_change_ticket — delegate execution-time ticket validation. */
    @PostMapping("/{changeTicketId}/validate")
    @Transactional
    public ResponseEntity<OpsEnvelope<ChangeTicketValidationResult>> validateForExecution(
            @PathVariable UUID changeTicketId,
            HttpServletRequest servletRequest,
            @Valid @RequestBody ChangeTicketValidateRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);
        ChangeTicketEntity ticket = changeTicketValidator.validate(changeTicketId, request.tenantId(), request.operation());
        auditService.record(
                ticket.getTenantId(),
                AuditService.ACTOR_SYSTEM,
                "change_ticket_validate",
                "change_ticket",
                ticket.getId(),
                "change ticket validated for execution");
        return ResponseEntity.ok(OpsEnvelope.ok(
                requestId,
                "validate_change_ticket",
                ChangeTicketValidationResult.from(ticket)));
    }

    /** get_change_ticket — fetch a single change ticket record. */
    @GetMapping("/{changeTicketId}")
    public ResponseEntity<OpsEnvelope<ChangeTicketResult>> getChangeTicket(
            @PathVariable UUID changeTicketId,
            @RequestParam UUID tenantId,
            HttpServletRequest servletRequest) {
        String requestId = AgentHeaders.requestId(servletRequest);
        ChangeTicketEntity ticket = changeTicketRepository.findByIdAndTenantId(changeTicketId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHANGE_TICKET_NOT_FOUND,
                        "change ticket not found: " + changeTicketId));
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_change_ticket", ChangeTicketResult.from(ticket)));
    }

    private ChangeTicketEntity requireChangeTicket(UUID changeTicketId, UUID tenantId) {
        return changeTicketRepository.findByIdAndTenantId(changeTicketId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHANGE_TICKET_NOT_FOUND,
                        "change ticket not found: " + changeTicketId));
    }

    private ChangeTicketEntity requireChangeTicketForUpdate(UUID changeTicketId, UUID tenantId) {
        return changeTicketRepository.findByIdAndTenantIdForUpdate(changeTicketId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHANGE_TICKET_NOT_FOUND,
                        "change ticket not found: " + changeTicketId));
    }

    private static void requireRequestActor(AuthenticatedUser actor, UUID tenantId, UUID decidedBy) {
        requireTenant(actor, tenantId);
        if (!actor.userId().equals(decidedBy)) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "change ticket approver scope mismatch");
        }
    }

    private static void requireTenant(AuthenticatedUser actor, UUID tenantId) {
        if (!actor.tenantId().equals(tenantId)) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "change ticket tenant scope mismatch");
        }
    }

    private static void requireDifferentApprover(AuthenticatedUser actor, UUID requiredApprover) {
        if (actor.userId().equals(requiredApprover)) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH,
                    "change ticket requester cannot approve their own ticket");
        }
    }

    private static void requireTrustedApprover(ChangeTicketEntity ticket, AuthenticatedUser actor) {
        if (ticket.getRequiredApprover() == null || !ticket.getRequiredApprover().equals(actor.userId())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH,
                    "change ticket approver mismatch: " + ticket.getId());
        }
    }

    private static void requireCompleteTicket(ChangeTicketEntity ticket) {
        if (ticket.getWindowStart() == null || ticket.getWindowEnd() == null) {
            throw new ApiException(ErrorCode.CHANGE_WINDOW_CLOSED,
                    "change ticket execution window is required");
        }
        if (isBlank(ticket.getRollbackPlan()) || isBlank(ticket.getImpactAnalysis())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                    "change ticket rollback plan and impact analysis are required");
        }
        if (isBlank(ticket.getScopeOperation())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                    "change ticket operation scope is required");
        }
        if (ticket.getRequiredApprover() == null) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH,
                    "change ticket required approver is required");
        }
        if (ticket.getRequestedBy() == null) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH,
                    "change ticket requester is required");
        }
        if (ticket.getRequestedBy().equals(ticket.getRequiredApprover())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH,
                    "change ticket requester cannot approve their own ticket");
        }
    }

    private static void validateWindowOrder(Instant windowStart, Instant windowEnd) {
        if (!windowEnd.isAfter(windowStart)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "windowEnd must be after windowStart");
        }
    }

    private static AuthenticatedUser authenticatedActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "change ticket approval requires authenticated actor");
        }
        return user;
    }

    private static String approvalDetail(String comment) {
        if (comment == null || comment.isBlank()) {
            return "status=APPROVED";
        }
        return "status=APPROVED; comment=" + comment.replaceAll("[\\r\\n]", " ");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void rejectUnknown(String name) {
        throw new IllegalArgumentException("unsupported field: " + name);
    }

    public record CreateChangeTicketRequest(
            @NotNull UUID tenantId,
            @NotBlank @Size(max = 255) String title,
            @JsonAlias({"scope_operation", "toolName"}) @NotBlank @Size(max = 100)
            @Pattern(regexp = "^[a-z][a-z0-9_]*$") String scopeOperation,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @JsonAlias("rollback_plan") @NotBlank @Size(max = 5000) String rollbackPlan,
            @JsonAlias({"impact", "impact_analysis"}) @NotBlank @Size(max = 5000) String impactAnalysis,
            @NotNull UUID requiredApprover
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            rejectUnknown(name);
        }
    }

    public record ChangeTicketApprovalRequest(
            @NotNull UUID tenantId,
            @NotNull UUID approvedBy,
            @Size(max = 500) String comment
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            rejectUnknown(name);
        }
    }

    public record ChangeTicketValidateRequest(
            @NotNull UUID tenantId,
            @JsonAlias({"scopeOperation", "scope_operation", "toolName"})
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z][a-z0-9_]*$") String operation
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            rejectUnknown(name);
        }
    }

    public record ChangeTicketResult(
            UUID changeTicketId,
            UUID tenantId,
            String title,
            String status,
            Instant createdAt,
            Instant windowStart,
            Instant windowEnd,
            String scopeOperation,
            String rollbackPlan,
            String impactAnalysis,
            UUID requiredApprover,
            UUID requestedBy,
            UUID approvedBy,
            Instant approvedAt
    ) {
        static ChangeTicketResult from(ChangeTicketEntity ticket) {
            return new ChangeTicketResult(
                    ticket.getId(),
                    ticket.getTenantId(),
                    ticket.getTitle(),
                    externalStatus(ticket.getStatus()),
                    ticket.getCreatedAt(),
                    ticket.getWindowStart(),
                    ticket.getWindowEnd(),
                    ticket.getScopeOperation(),
                    ticket.getRollbackPlan(),
                    ticket.getImpactAnalysis(),
                    ticket.getRequiredApprover(),
                    ticket.getRequestedBy(),
                    ticket.getApprovedBy(),
                    ticket.getApprovedAt());
        }
    }

    public record ChangeTicketValidationResult(
            UUID changeTicketId,
            String status,
            boolean valid
    ) {
        static ChangeTicketValidationResult from(ChangeTicketEntity ticket) {
            return new ChangeTicketValidationResult(
                    ticket.getId(),
                    "validated",
                    true);
        }
    }

    private static String externalStatus(String status) {
        return STATUS_OPEN.equals(status) ? "pending" : status.toLowerCase(Locale.ROOT);
    }
}
