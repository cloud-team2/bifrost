package com.bifrost.ops.governance.changemanagement;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 변경 티켓 유효성 검증(S3). */
@Service
public class ChangeTicketValidator {

    private final ChangeTicketRepository repository;

    public ChangeTicketValidator(ChangeTicketRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ChangeTicketEntity validate(UUID ticketId, UUID tenantId) {
        ChangeTicketEntity ticket = repository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHANGE_TICKET_NOT_FOUND, "change ticket not found: " + ticketId));
        if (!"OPEN".equals(ticket.getStatus())) {
            throw new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED, "change ticket not OPEN: " + ticket.getStatus());
        }
        return ticket;
    }
}
