package com.bifrost.ops.governance.changemanagement;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 변경 티켓 유효성 검증(S3). */
@Service
public class ChangeTicketValidator {

    private static final String STATUS_APPROVED = "APPROVED";

    private final ChangeTicketRepository repository;

    public ChangeTicketValidator(ChangeTicketRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ChangeTicketEntity validate(UUID ticketId, UUID tenantId) {
        return validate(ticketId, tenantId, null);
    }

    @Transactional(readOnly = true)
    public ChangeTicketEntity validate(UUID ticketId, UUID tenantId, String operation) {
        ChangeTicketEntity ticket = repository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHANGE_TICKET_NOT_FOUND, "change ticket not found: " + ticketId));
        if (!STATUS_APPROVED.equals(ticket.getStatus())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                    "change ticket not APPROVED: " + ticket.getStatus());
        }
        if (ticket.getRequiredApprover() == null || ticket.getRequestedBy() == null
                || ticket.getApprovedBy() == null || ticket.getApprovedAt() == null
                || !ticket.getRequiredApprover().equals(ticket.getApprovedBy())
                || ticket.getRequestedBy().equals(ticket.getApprovedBy())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                    "change ticket approval metadata is required");
        }
        Instant now = Instant.now();
        if (ticket.getWindowStart() == null || ticket.getWindowEnd() == null) {
            throw new ApiException(ErrorCode.CHANGE_WINDOW_CLOSED,
                    "change ticket execution window is required");
        }
        if (now.isBefore(ticket.getWindowStart())) {
            throw new ApiException(ErrorCode.CHANGE_WINDOW_CLOSED,
                    "change ticket execution window has not opened yet");
        }
        if (now.isAfter(ticket.getWindowEnd())) {
            throw new ApiException(ErrorCode.CHANGE_WINDOW_CLOSED,
                    "change ticket outside execution window");
        }
        if (isBlank(ticket.getRollbackPlan()) || isBlank(ticket.getImpactAnalysis())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                    "change ticket rollback plan and impact analysis are required");
        }
        if (isBlank(ticket.getScopeOperation())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED,
                    "change ticket operation scope is required");
        }
        if (operation != null && !ticket.getScopeOperation().equals(operation)) {
            throw new ApiException(ErrorCode.CHANGE_SCOPE_MISMATCH,
                    "change ticket operation scope mismatch");
        }
        return ticket;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
