package com.bifrost.ops.governance.changemanagement.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.governance.changemanagement.ChangeTicketValidator;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChangeTicketController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = {
        "spring.mvc.throw-exception-if-no-handler-found=true",
        "spring.web.resources.add-mappings=false"
})
class ChangeTicketControllerTest {

    private static final String REQUEST_ID = "req-change-1";
    private static final String REQUEST_ID_HEADER = "X-Agent-Request-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ChangeTicketRepository changeTicketRepository;

    @MockBean
    private ChangeTicketValidator changeTicketValidator;

    @MockBean
    private AuditService auditService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createChangeTicketPersistsOpenTicketAndReturnsPendingEnvelope() throws Exception {
        when(changeTicketRepository.save(any(ChangeTicketEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID tenantId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID requiredApprover = UUID.randomUUID();
        Instant windowStart = Instant.parse("2026-06-10T10:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-10T11:00:00Z");
        authenticate(requester, tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"Reset offsets for stuck consumer group",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"%s",
                                  "windowEnd":"%s",
                                  "rollbackPlan":"restore committed offsets from snapshot",
                                  "impact":"consumer group pauses during reset",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(tenantId, windowStart, windowEnd, requiredApprover)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("create_change_ticket"))
                .andExpect(jsonPath("$.result.status").value("pending"))
                .andExpect(jsonPath("$.result.title").value("Reset offsets for stuck consumer group"))
                .andExpect(jsonPath("$.result.scopeOperation").value("reset_offsets"))
                .andExpect(jsonPath("$.result.rollbackPlan").value("restore committed offsets from snapshot"))
                .andExpect(jsonPath("$.result.impactAnalysis").value("consumer group pauses during reset"))
                .andExpect(jsonPath("$.result.requiredApprover").value(requiredApprover.toString()))
                .andExpect(jsonPath("$.result.requestedBy").value(requester.toString()));

        ArgumentCaptor<ChangeTicketEntity> captor = ArgumentCaptor.forClass(ChangeTicketEntity.class);
        verify(changeTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().getTitle()).isEqualTo("Reset offsets for stuck consumer group");
        assertThat(captor.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(captor.getValue().getWindowStart()).isEqualTo(windowStart);
        assertThat(captor.getValue().getWindowEnd()).isEqualTo(windowEnd);
        assertThat(captor.getValue().getRollbackPlan()).isEqualTo("restore committed offsets from snapshot");
        assertThat(captor.getValue().getImpactAnalysis()).isEqualTo("consumer group pauses during reset");
        assertThat(captor.getValue().getScopeOperation()).isEqualTo("reset_offsets");
        assertThat(captor.getValue().getRequiredApprover()).isEqualTo(requiredApprover);
        assertThat(captor.getValue().getRequestedBy()).isEqualTo(requester);
        verify(auditService).record(
                eq(tenantId),
                eq(requester.toString()),
                eq("create_change_ticket"),
                eq("change_ticket"),
                eq(captor.getValue().getId()),
                eq("title=Reset offsets for stuck consumer group"));
    }

    @Test
    void createChangeTicketRejectsBlankTitleBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"2026-06-10T10:00:00Z",
                                  "windowEnd":"2026-06-10T11:00:00Z",
                                  "rollbackPlan":"restore offsets",
                                  "impact":"consumer delay",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsMissingGovernanceFieldsBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"Reset offsets",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"2026-06-10T10:00:00Z",
                                  "windowEnd":"2026-06-10T11:00:00Z",
                                  "rollbackPlan":"",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsMissingTenantBeforeSaving() throws Exception {
        authenticate(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Reset offsets",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"2026-06-10T10:00:00Z",
                                  "windowEnd":"2026-06-10T11:00:00Z",
                                  "rollbackPlan":"restore offsets",
                                  "impact":"consumer delay",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsUnsupportedMetadataBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"Reset offsets",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"2026-06-10T10:00:00Z",
                                  "windowEnd":"2026-06-10T11:00:00Z",
                                  "rollbackPlan":"restore offsets",
                                  "impact":"consumer delay",
                                  "requiredApprover":"%s",
                                  "paramsHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                }
                                """.formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsInvalidWindowBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"Reset offsets",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"2026-06-10T11:00:00Z",
                                  "windowEnd":"2026-06-10T10:00:00Z",
                                  "rollbackPlan":"restore offsets",
                                  "impact":"consumer delay",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsMissingOperationBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"Reset offsets",
                                  "windowStart":"2026-06-10T10:00:00Z",
                                  "windowEnd":"2026-06-10T11:00:00Z",
                                  "rollbackPlan":"restore offsets",
                                  "impact":"consumer delay",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketAcceptsSnakeCaseScopeOperationAlias() throws Exception {
        when(changeTicketRepository.save(any(ChangeTicketEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload(tenantId, "\"scope_operation\"", "update_connector")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.scopeOperation").value("update_connector"));

        ArgumentCaptor<ChangeTicketEntity> captor = ArgumentCaptor.forClass(ChangeTicketEntity.class);
        verify(changeTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getScopeOperation()).isEqualTo("update_connector");
        assertThat(captor.getValue().getTitle()).isEqualTo("Change for update_connector");
    }

    @Test
    void createChangeTicketAcceptsToolNameAsOperationAlias() throws Exception {
        when(changeTicketRepository.save(any(ChangeTicketEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload(tenantId, "\"toolName\"", "reset_offsets")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.scopeOperation").value("reset_offsets"));

        ArgumentCaptor<ChangeTicketEntity> captor = ArgumentCaptor.forClass(ChangeTicketEntity.class);
        verify(changeTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getScopeOperation()).isEqualTo("reset_offsets");
        assertThat(captor.getValue().getTitle()).isEqualTo("Change for reset_offsets");
    }

    @Test
    void createChangeTicketRejectsInvalidOperationFormatBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        authenticate(UUID.randomUUID(), tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload(tenantId, "\"scopeOperation\"", "reset-offsets")))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRequiresAuthenticatedRequesterBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload(UUID.randomUUID(), "\"scopeOperation\"", "reset_offsets")))
                .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.UNAUTHENTICATED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsRequesterAsRequiredApproverBeforeSaving() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        authenticate(requester, tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "title":"Reset offsets",
                                  "scopeOperation":"reset_offsets",
                                  "windowStart":"2026-06-10T10:00:00Z",
                                  "windowEnd":"2026-06-10T11:00:00Z",
                                  "rollbackPlan":"restore offsets",
                                  "impact":"consumer delay",
                                  "requiredApprover":"%s"
                                }
                                """.formatted(tenantId, requester)))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void validateChangeTicketDelegatesToValidatorAndAudits() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "reset_offsets", "APPROVED");
        when(changeTicketValidator.validate(ticketId, tenantId, "reset_offsets")).thenReturn(ticket);

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", ticketId)
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","scopeOperation":"reset_offsets"}
                                """.formatted(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("validate_change_ticket"))
                .andExpect(jsonPath("$.result.changeTicketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.result.status").value("validated"))
                .andExpect(jsonPath("$.result.valid").value(true));

        verify(changeTicketValidator).validate(ticketId, tenantId, "reset_offsets");
        verify(auditService).record(
                eq(tenantId),
                eq(AuditService.ACTOR_SYSTEM),
                eq("change_ticket_validate"),
                eq("change_ticket"),
                eq(ticketId),
                eq("change ticket validated for execution"));
    }

    @Test
    void validateChangeTicketCanCheckOperationScope() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "reset_offsets", "APPROVED");
        when(changeTicketValidator.validate(ticketId, tenantId, "reset_offsets")).thenReturn(ticket);

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", ticketId)
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","scopeOperation":"reset_offsets"}
                                """.formatted(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("validate_change_ticket"));

        verify(changeTicketValidator).validate(ticketId, tenantId, "reset_offsets");
    }

    @Test
    void validateChangeTicketAcceptsToolNameOperationAlias() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "update_connector", "APPROVED");
        when(changeTicketValidator.validate(ticketId, tenantId, "update_connector")).thenReturn(ticket);

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","toolName":"update_connector"}
                                """.formatted(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("validate_change_ticket"));

        verify(changeTicketValidator).validate(ticketId, tenantId, "update_connector");
    }

    @Test
    void validateChangeTicketRejectsInvalidOperationBeforeDelegating() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","scopeOperation":"reset-offsets"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketValidator, never()).validate(any(), any(), any());
    }

    @Test
    void approveChangeTicketTransitionsOpenTicketAndAudits() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "reset_offsets", "OPEN");
        ticket.setRequiredApprover(approver);
        authenticate(approver, tenantId);
        when(changeTicketRepository.findByIdAndTenantIdForUpdate(ticketId, tenantId)).thenReturn(Optional.of(ticket));
        when(changeTicketRepository.save(any(ChangeTicketEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/approve", ticketId)
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","approvedBy":"%s","comment":"verified rollback"}
                                """.formatted(tenantId, approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("approve_change_ticket"))
                .andExpect(jsonPath("$.result.changeTicketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.result.status").value("approved"))
                .andExpect(jsonPath("$.result.approvedBy").value(approver.toString()))
                .andExpect(jsonPath("$.result.approvedAt").exists());

        ArgumentCaptor<ChangeTicketEntity> captor = ArgumentCaptor.forClass(ChangeTicketEntity.class);
        verify(changeTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(captor.getValue().getApprovedBy()).isEqualTo(approver);
        assertThat(captor.getValue().getApprovedAt()).isNotNull();
        verify(auditService).record(
                eq(tenantId),
                eq(approver.toString()),
                eq("change_ticket_approve"),
                eq("change_ticket"),
                eq(ticketId),
                eq("status=APPROVED; comment=verified rollback"));
    }

    @Test
    void approveChangeTicketRejectsUnauthenticatedActorBeforeLookup() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/approve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","approvedBy":"%s"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.UNAUTHENTICATED)));

        verify(changeTicketRepository, never()).findByIdAndTenantId(any(), any());
        verify(changeTicketRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void approveChangeTicketRejectsApproverMismatchBeforeSaving() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID requiredApprover = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "reset_offsets", "OPEN");
        ticket.setRequiredApprover(requiredApprover);
        authenticate(decidedBy, tenantId);
        when(changeTicketRepository.findByIdAndTenantIdForUpdate(ticketId, tenantId)).thenReturn(Optional.of(ticket));

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/approve", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","approvedBy":"%s"}
                                """.formatted(tenantId, decidedBy)))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void approveChangeTicketRejectsBodyActorMismatchBeforeLookup() throws Exception {
        UUID authenticatedApprover = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        authenticate(authenticatedApprover, tenantId);

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/approve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","approvedBy":"%s"}
                                """.formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));

        verify(changeTicketRepository, never()).findByIdAndTenantId(any(), any());
        verify(changeTicketRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void approveChangeTicketRejectsAlreadyApprovedTicket() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "reset_offsets", "APPROVED");
        ticket.setRequiredApprover(approver);
        authenticate(approver, tenantId);
        when(changeTicketRepository.findByIdAndTenantIdForUpdate(ticketId, tenantId)).thenReturn(Optional.of(ticket));

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/approve", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","approvedBy":"%s"}
                                """.formatted(tenantId, approver)))
                .andExpect(status().is(ErrorCode.APPROVAL_ALREADY_DECIDED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_ALREADY_DECIDED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void validateChangeTicketPropagatesValidatorErrorCode() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(changeTicketValidator.validate(ticketId, tenantId, "reset_offsets"))
                .thenThrow(new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED, "change ticket not OPEN"));

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","scopeOperation":"reset_offsets"}
                                """.formatted(tenantId)))
                .andExpect(status().is(ErrorCode.CHANGE_TICKET_REQUIRED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.CHANGE_TICKET_REQUIRED)));
    }

    @Test
    void validateChangeTicketMissingTenantReturnsValidationFailedCode() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeOperation":"reset_offsets"}
                                """))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    @Test
    void validateChangeTicketMissingOperationReturnsValidationFailedCode() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketValidator, never()).validate(any(), any());
        verify(changeTicketValidator, never()).validate(any(), any(), any());
    }

    @Test
    void validateChangeTicketRejectsUnsupportedMetadataBeforeDelegating() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "scopeOperation":"reset_offsets",
                                  "paramsHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketValidator, never()).validate(any(), any());
    }

    @Test
    void getChangeTicketReturnsEnvelope() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "reset_offsets", "OPEN");
        when(changeTicketRepository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.of(ticket));

        mockMvc.perform(get("/internal/ops/change-tickets/{changeTicketId}", ticketId)
                        .param("tenantId", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("get_change_ticket"))
                .andExpect(jsonPath("$.result.changeTicketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.result.status").value("pending"))
                .andExpect(jsonPath("$.result.scopeOperation").value("reset_offsets"));
    }

    @Test
    void getChangeTicketReturnsNotFoundCode() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(changeTicketRepository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/ops/change-tickets/{changeTicketId}", ticketId)
                        .param("tenantId", tenantId.toString()))
                .andExpect(status().is(ErrorCode.CHANGE_TICKET_NOT_FOUND.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.CHANGE_TICKET_NOT_FOUND)));
    }

    @Test
    void getChangeTicketMissingTenantReturnsValidationFailedCode() throws Exception {
        mockMvc.perform(get("/internal/ops/change-tickets/{changeTicketId}", UUID.randomUUID()))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    @Test
    void getChangeTicketMalformedIdReturnsValidationFailedCode() throws Exception {
        mockMvc.perform(get("/internal/ops/change-tickets/{changeTicketId}", "not-a-uuid")
                        .param("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    private static ChangeTicketEntity ticket(UUID id, UUID tenantId, String title, String status) {
        ChangeTicketEntity ticket = new ChangeTicketEntity();
        ticket.setId(id);
        ticket.setTenantId(tenantId);
        ticket.setTitle("Change for " + title);
        ticket.setStatus(status);
        ticket.setWindowStart(Instant.now().minusSeconds(60));
        ticket.setWindowEnd(Instant.now().plusSeconds(60));
        ticket.setRollbackPlan("restore previous connector config");
        ticket.setImpactAnalysis("single connector impact");
        ticket.setScopeOperation(title);
        ticket.setRequestedBy(UUID.randomUUID());
        ticket.setRequiredApprover(UUID.randomUUID());
        return ticket;
    }

    private static String validCreatePayload(UUID tenantId, String operationField, String operation) {
        return """
                {
                  "tenantId":"%s",
                  "title":"Change for %s",
                  %s:"%s",
                  "windowStart":"2026-06-10T10:00:00Z",
                  "windowEnd":"2026-06-10T11:00:00Z",
                  "rollbackPlan":"restore previous config",
                  "impact":"single connector impact",
                  "requiredApprover":"%s"
                }
                """.formatted(tenantId, operation, operationField, operation, UUID.randomUUID());
    }

    private static void authenticate(UUID userId, UUID tenantId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, tenantId, "operator@bifrost.io");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a"));
    }

    private static String code(ErrorCode code) {
        return String.valueOf(code.code());
    }
}
