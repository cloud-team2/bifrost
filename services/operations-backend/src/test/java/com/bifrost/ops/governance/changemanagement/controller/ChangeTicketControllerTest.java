package com.bifrost.ops.governance.changemanagement.controller;

import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.governance.changemanagement.ChangeTicketValidator;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void createChangeTicketPersistsOpenTicketAndReturnsPendingEnvelope() throws Exception {
        when(changeTicketRepository.save(any(ChangeTicketEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/internal/ops/change-tickets")
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"rollback_pipeline"
                                }
                                """.formatted(tenantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("create_change_ticket"))
                .andExpect(jsonPath("$.result.status").value("pending"))
                .andExpect(jsonPath("$.result.title").value("rollback_pipeline"));

        ArgumentCaptor<ChangeTicketEntity> captor = ArgumentCaptor.forClass(ChangeTicketEntity.class);
        verify(changeTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().getTitle()).isEqualTo("rollback_pipeline");
        assertThat(captor.getValue().getStatus()).isEqualTo("OPEN");
        verify(auditService).record(
                eq(tenantId),
                eq(AuditService.ACTOR_SYSTEM),
                eq("create_change_ticket"),
                eq("change_ticket"),
                eq(captor.getValue().getId()),
                eq("title=rollback_pipeline"));
    }

    @Test
    void createChangeTicketRejectsBlankTitleBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","toolName":""}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsMissingTenantBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolName":"rollback_pipeline"}
                                """))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void createChangeTicketRejectsUnsupportedMetadataBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"rollback_pipeline",
                                  "paramsHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "rollbackPlan":"restore previous config"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(changeTicketRepository, never()).save(any());
    }

    @Test
    void validateChangeTicketDelegatesToValidatorAndAudits() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "rollback_pipeline", "OPEN");
        when(changeTicketValidator.validate(ticketId, tenantId)).thenReturn(ticket);

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", ticketId)
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s"}
                                """.formatted(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("validate_change_ticket"))
                .andExpect(jsonPath("$.result.changeTicketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.result.status").value("validated"))
                .andExpect(jsonPath("$.result.valid").value(true));

        verify(changeTicketValidator).validate(ticketId, tenantId);
        verify(auditService).record(
                eq(tenantId),
                eq(AuditService.ACTOR_SYSTEM),
                eq("change_ticket_validate"),
                eq("change_ticket"),
                eq(ticketId),
                eq("change ticket validated for execution"));
    }

    @Test
    void validateChangeTicketPropagatesValidatorErrorCode() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(changeTicketValidator.validate(ticketId, tenantId))
                .thenThrow(new ApiException(ErrorCode.CHANGE_TICKET_REQUIRED, "change ticket not OPEN"));

        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s"}
                                """.formatted(tenantId)))
                .andExpect(status().is(ErrorCode.CHANGE_TICKET_REQUIRED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.CHANGE_TICKET_REQUIRED)));
    }

    @Test
    void validateChangeTicketMissingTenantReturnsValidationFailedCode() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    @Test
    void validateChangeTicketRejectsUnsupportedMetadataBeforeDelegating() throws Exception {
        mockMvc.perform(post("/internal/ops/change-tickets/{changeTicketId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
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
        ChangeTicketEntity ticket = ticket(ticketId, tenantId, "rollback_pipeline", "OPEN");
        when(changeTicketRepository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.of(ticket));

        mockMvc.perform(get("/internal/ops/change-tickets/{changeTicketId}", ticketId)
                        .param("tenantId", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("get_change_ticket"))
                .andExpect(jsonPath("$.result.changeTicketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.result.status").value("pending"));
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
        ticket.setTitle(title);
        ticket.setStatus(status);
        return ticket;
    }

    private static String code(ErrorCode code) {
        return String.valueOf(code.code());
    }
}
