package com.bifrost.ops.governance.changemanagement.controller;

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
import jakarta.validation.constraints.Size;
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

        ChangeTicketEntity ticket = new ChangeTicketEntity();
        ticket.setId(UUID.randomUUID());
        ticket.setTenantId(request.tenantId());
        ticket.setTitle(request.title());
        ticket.setStatus(STATUS_OPEN);
        ticket.setScopeOperation(request.title());

        ChangeTicketEntity saved = changeTicketRepository.save(ticket);
        auditService.record(
                saved.getTenantId(),
                AuditService.ACTOR_SYSTEM,
                "create_change_ticket",
                "change_ticket",
                saved.getId(),
                "title=" + saved.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OpsEnvelope.ok(requestId, "create_change_ticket", ChangeTicketResult.from(saved)));
    }

    /** validate_change_ticket — delegate execution-time ticket validation. */
    @PostMapping("/{changeTicketId}/validate")
    @Transactional
    public ResponseEntity<OpsEnvelope<ChangeTicketValidationResult>> validateForExecution(
            @PathVariable UUID changeTicketId,
            HttpServletRequest servletRequest,
            @Valid @RequestBody ChangeTicketValidateRequest request) {
        String requestId = AgentHeaders.requestId(servletRequest);
        ChangeTicketEntity ticket = changeTicketValidator.validate(changeTicketId, request.tenantId());
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

    public record CreateChangeTicketRequest(
            @NotNull UUID tenantId,
            @JsonAlias("toolName") @NotBlank @Size(max = 255) String title
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unsupported field: " + name);
        }
    }

    public record ChangeTicketValidateRequest(@NotNull UUID tenantId) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unsupported field: " + name);
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
            String scopeOperation
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
                    ticket.getScopeOperation());
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
