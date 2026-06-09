package com.bifrost.ops.governance.changemanagement;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeTicketValidatorTest {

    private final ChangeTicketRepository repository = mock(ChangeTicketRepository.class);
    private final ChangeTicketValidator validator = new ChangeTicketValidator(repository);

    @Test
    void validatesOpenTicketInTenantScope() {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "OPEN");
        when(repository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.of(ticket));

        assertThat(validator.validate(ticketId, tenantId)).isSameAs(ticket);
    }

    @Test
    void outOfScopeTicketUsesNotFoundCode() {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.empty());

        assertApiCode(() -> validator.validate(ticketId, tenantId), ErrorCode.CHANGE_TICKET_NOT_FOUND);
    }

    @Test
    void nonOpenTicketUsesRequiredCode() {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.of(ticket(ticketId, tenantId, "CLOSED")));

        assertApiCode(() -> validator.validate(ticketId, tenantId), ErrorCode.CHANGE_TICKET_REQUIRED);
    }

    private static ChangeTicketEntity ticket(UUID id, UUID tenantId, String status) {
        ChangeTicketEntity ticket = new ChangeTicketEntity();
        ticket.setId(id);
        ticket.setTenantId(tenantId);
        ticket.setTitle("rollback_pipeline");
        ticket.setStatus(status);
        return ticket;
    }

    private static void assertApiCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(expected));
    }
}
