package com.bifrost.ops.governance.approval.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import com.bifrost.ops.governance.approval.ApprovalValidator;
import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.approval.persistence.repository.ApprovalRepository;
import com.bifrost.ops.governance.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
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

@WebMvcTest(ApprovalController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = {
        "spring.mvc.throw-exception-if-no-handler-found=true",
        "spring.web.resources.add-mappings=false"
})
class ApprovalControllerTest {

    private static final String REQUEST_ID = "req-approval-1";
    private static final String REQUEST_ID_HEADER = "X-Agent-Request-Id";
    private static final String PARAMS_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ApprovalRepository approvalRepository;

    @MockBean
    private ApprovalValidator approvalValidator;

    @MockBean
    private AuditService auditService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createApprovalPersistsPendingRecordAndReturnsEnvelope() throws Exception {
        when(approvalRepository.save(any(ApprovalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID tenantId = UUID.randomUUID();
        UUID requiredApprover = UUID.randomUUID();

        mockMvc.perform(post("/internal/ops/approvals")
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"%s",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":30
                                }
                                """.formatted(tenantId, PARAMS_HASH, requiredApprover)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("create_approval"))
                .andExpect(jsonPath("$.result.status").value("pending"))
                .andExpect(jsonPath("$.result.operation").value("restart_connector_task"))
                .andExpect(jsonPath("$.result.paramsHash").value(PARAMS_HASH));

        ArgumentCaptor<ApprovalEntity> captor = ArgumentCaptor.forClass(ApprovalEntity.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().getActor()).isEqualTo(requiredApprover.toString());
        assertThat(captor.getValue().getDecision()).isEqualTo("PENDING");
        verify(auditService).record(
                eq(tenantId),
                eq(AuditService.ACTOR_SYSTEM),
                eq("create_approval"),
                eq("approval"),
                eq(captor.getValue().getId()),
                eq("operation=restart_connector_task"));
    }

    @Test
    void createApprovalRejectsInvalidTtlBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"%s",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":0
                                }
                                """.formatted(UUID.randomUUID(), PARAMS_HASH, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsTtlAboveMaximumBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"%s",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":1441
                                }
                                """.formatted(UUID.randomUUID(), PARAMS_HASH, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsNonHashParamsHashBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"abc123",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":30
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsUppercaseParamsHashBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":30
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsUnknownIssueMetadataBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "actionId":"act_001",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"%s",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":30
                                }
                                """.formatted(UUID.randomUUID(), PARAMS_HASH, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsMissingTenantBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"%s",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":30
                                }
                                """.formatted(PARAMS_HASH, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsBlankToolNameBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"",
                                  "paramsHash":"%s",
                                  "requiredApprover":"%s",
                                  "expiresInMinutes":30
                                }
                                """.formatted(UUID.randomUUID(), PARAMS_HASH, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createApprovalRejectsBlankRequiredApproverBeforeSaving() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "toolName":"restart_connector_task",
                                  "paramsHash":"%s",
                                  "requiredApprover":"",
                                  "expiresInMinutes":30
                                }
                                """.formatted(UUID.randomUUID(), PARAMS_HASH, UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decideApprovalApprovesPendingRecordAndAudits() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(600), null);
        approval.setActor(decidedBy.toString());
        authenticate(decidedBy, approval.getTenantId());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));
        when(approvalRepository.save(any(ApprovalEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s","comment":"checked"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("decide_approval"))
                .andExpect(jsonPath("$.result.approvalId").value(approvalId.toString()))
                .andExpect(jsonPath("$.result.status").value("approved"))
                .andExpect(jsonPath("$.result.paramsHash").doesNotExist());

        verify(auditService).record(
                eq(approval.getTenantId()),
                eq(decidedBy.toString()),
                eq("approval_decision"),
                eq("approval"),
                eq(approvalId),
                eq("decision=APPROVED; comment=checked"));
    }

    @Test
    void decideApprovalRejectsPendingRecord() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(600), null);
        approval.setActor(decidedBy.toString());
        authenticate(decidedBy, approval.getTenantId());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));
        when(approvalRepository.save(any(ApprovalEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"rejected","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("rejected"));
    }

    @Test
    void decideApprovalRejectsInvalidDecisionBeforeLookup() throws Exception {
        authenticate(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"maybe","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    @Test
    void decideApprovalRejectsActorMismatchBeforeSaving() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID requiredApprover = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(600), null);
        approval.setActor(requiredApprover.toString());
        authenticate(decidedBy, approval.getTenantId());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decideApprovalRejectsBodyActorMismatchBeforeSaving() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID authenticatedApprover = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(600), null);
        approval.setActor(authenticatedApprover.toString());
        authenticate(authenticatedApprover, approval.getTenantId());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decideApprovalRejectsAuthenticatedTenantMismatchBeforeSaving() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(600), null);
        approval.setActor(decidedBy.toString());
        authenticate(decidedBy, UUID.randomUUID());

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));

        verify(approvalRepository, never()).findByIdAndTenantId(any(), any());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decideApprovalRequiresAuthenticatedActorBeforeLookup() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.UNAUTHENTICATED)));

        verify(approvalRepository, never()).findByIdAndTenantId(any(), any());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decideApprovalRejectsUnknownFieldBeforeLookup() throws Exception {
        authenticate(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision":"approved",
                                  "tenantId":"%s",
                                  "decidedBy":"%s",
                                  "actionId":"act_001"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void decideApprovalReturnsExpiredCode() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().minusSeconds(1), null);
        approval.setActor(decidedBy.toString());
        authenticate(decidedBy, approval.getTenantId());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().is(ErrorCode.APPROVAL_EXPIRED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_EXPIRED)));
    }

    @Test
    void decideApprovalReturnsAlreadyDecidedCode() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "APPROVED", Instant.now().plusSeconds(600), null);
        approval.setActor(decidedBy.toString());
        authenticate(decidedBy, approval.getTenantId());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approved","tenantId":"%s","decidedBy":"%s"}
                                """.formatted(approval.getTenantId(), decidedBy)))
                .andExpect(status().is(ErrorCode.APPROVAL_ALREADY_DECIDED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_ALREADY_DECIDED)));
    }

    @Test
    void validateForExecutionDelegatesToValidatorAndReturnsRedactedEnvelope() throws Exception {
        UUID approvalId = UUID.randomUUID();
        Instant usedAt = Instant.now();
        ApprovalEntity consumed = approval(approvalId, "APPROVED", Instant.now().plusSeconds(600), usedAt);
        when(approvalRepository.findByIdAndTenantId(approvalId, consumed.getTenantId())).thenReturn(Optional.of(consumed));
        when(approvalValidator.validateAndConsume(approvalId, PARAMS_HASH)).thenReturn(consumed);

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", approvalId)
                        .header(REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","paramsHash":"%s"}
                                """.formatted(consumed.getTenantId(), PARAMS_HASH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.operation").value("validate_approval"))
                .andExpect(jsonPath("$.result.status").value("validated"))
                .andExpect(jsonPath("$.result.paramsHash").doesNotExist());

        verify(approvalValidator).validateAndConsume(approvalId, PARAMS_HASH);
        verify(auditService).record(
                eq(consumed.getTenantId()),
                eq(AuditService.ACTOR_SYSTEM),
                eq("approval_validate"),
                eq("approval"),
                eq(approvalId),
                eq("approval validated for execution"));
    }

    @Test
    void validateForExecutionRejectsBlankParamsHashBeforeDelegating() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","paramsHash":""}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalValidator, never()).validateAndConsume(any(), any());
    }

    @Test
    void validateForExecutionRejectsUppercaseParamsHashBeforeDelegating() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","paramsHash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalValidator, never()).validateAndConsume(any(), any());
    }

    @Test
    void validateForExecutionRejectsWrongTenantBeforeDelegating() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(approvalRepository.findByIdAndTenantId(approvalId, tenantId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","paramsHash":"%s"}
                                """.formatted(tenantId, PARAMS_HASH)))
                .andExpect(status().is(ErrorCode.APPROVAL_NOT_FOUND.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_NOT_FOUND)));

        verify(approvalValidator, never()).validateAndConsume(any(), any());
        verify(auditService, never()).record(eq(tenantId), eq(AuditService.ACTOR_SYSTEM),
                eq("approval_validate"), eq("approval"), eq(approvalId), any());
    }

    @Test
    void validateForExecutionRejectsUnknownFieldBeforeDelegating() throws Exception {
        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId":"%s",
                                  "paramsHash":"%s",
                                  "actionId":"act_001"
                                }
                                """.formatted(UUID.randomUUID(), PARAMS_HASH)))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalValidator, never()).validateAndConsume(any(), any());
    }

    @Test
    void validateForExecutionPropagatesValidatorErrorCode() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "APPROVED", Instant.now().plusSeconds(600), null);
        approval.setActor(decidedBy.toString());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));
        when(approvalValidator.validateAndConsume(approvalId, PARAMS_HASH))
                .thenThrow(new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "approval params_hash mismatch"));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","paramsHash":"%s"}
                                """.formatted(approval.getTenantId(), PARAMS_HASH)))
                .andExpect(status().is(ErrorCode.APPROVAL_SCOPE_MISMATCH.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_SCOPE_MISMATCH)));
    }

    @Test
    void validateForExecutionReturnsValidatorAlreadyUsedCode() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "APPROVED", Instant.now().plusSeconds(600), null);
        approval.setActor(decidedBy.toString());
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));
        when(approvalValidator.validateAndConsume(approvalId, PARAMS_HASH))
                .thenThrow(new ApiException(ErrorCode.APPROVAL_ALREADY_USED, "approval already used"));

        mockMvc.perform(post("/internal/ops/approvals/{approvalId}/validate", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","paramsHash":"%s"}
                                """.formatted(approval.getTenantId(), PARAMS_HASH)))
                .andExpect(status().is(ErrorCode.APPROVAL_ALREADY_USED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_ALREADY_USED)));
    }

    @Test
    void getApprovalReturnsRedactedEnvelope() throws Exception {
        UUID approvalId = UUID.randomUUID();
        ApprovalEntity approval = approval(approvalId, "PENDING", Instant.now().plusSeconds(600), null);
        when(approvalRepository.findByIdAndTenantId(approvalId, approval.getTenantId())).thenReturn(Optional.of(approval));

        mockMvc.perform(get("/internal/ops/approvals/{approvalId}", approvalId)
                        .param("tenantId", approval.getTenantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("get_approval"))
                .andExpect(jsonPath("$.result.approvalId").value(approvalId.toString()))
                .andExpect(jsonPath("$.result.paramsHash").doesNotExist());
    }

    @Test
    void getApprovalReturnsNotFoundCode() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(approvalRepository.findByIdAndTenantId(approvalId, tenantId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/ops/approvals/{approvalId}", approvalId)
                        .param("tenantId", tenantId.toString()))
                .andExpect(status().is(ErrorCode.APPROVAL_NOT_FOUND.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.APPROVAL_NOT_FOUND)));
    }

    @Test
    void getApprovalMissingTenantReturnsValidationFailed() throws Exception {
        mockMvc.perform(get("/internal/ops/approvals/{approvalId}", UUID.randomUUID()))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    @Test
    void getApprovalMalformedIdReturnsValidationFailed() throws Exception {
        mockMvc.perform(get("/internal/ops/approvals/{approvalId}", "not-a-uuid")
                        .param("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));
    }

    @Test
    void listApprovalsFiltersByTenantStatusAndActor() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ApprovalEntity pendingForActor = approval(UUID.randomUUID(), tenantId, "PENDING", Instant.now().plusSeconds(600), null);
        pendingForActor.setActor("operator-a");
        when(approvalRepository.findByTenantIdAndDecisionIgnoreCaseAndActor(
                eq(tenantId), eq("pending"), eq("operator-a"), any()))
                .thenReturn(List.of(pendingForActor));

        mockMvc.perform(get("/internal/ops/approvals")
                        .param("tenantId", tenantId.toString())
                        .param("status", "pending")
                        .param("actorId", "operator-a")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("list_approvals"))
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].approvalId").value(pendingForActor.getId().toString()))
                .andExpect(jsonPath("$.result[0].paramsHash").doesNotExist());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(approvalRepository).findByTenantIdAndDecisionIgnoreCaseAndActor(
                eq(tenantId), eq("pending"), eq("operator-a"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void listApprovalsDefaultsToPendingAndLimit100() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ApprovalEntity pending = approval(UUID.randomUUID(), tenantId, "PENDING", Instant.now().plusSeconds(600), null);
        when(approvalRepository.findByTenantIdAndDecisionIgnoreCase(eq(tenantId), eq("PENDING"), any()))
                .thenReturn(List.of(pending));

        mockMvc.perform(get("/internal/ops/approvals")
                        .param("tenantId", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(approvalRepository).findByTenantIdAndDecisionIgnoreCase(
                eq(tenantId), eq("PENDING"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void listApprovalsRejectsLimitBelowRangeBeforeQuery() throws Exception {
        mockMvc.perform(get("/internal/ops/approvals")
                        .param("tenantId", UUID.randomUUID().toString())
                        .param("limit", "0"))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).findByTenantIdAndDecisionIgnoreCase(any(), any(), any());
        verify(approvalRepository, never()).findByTenantIdAndDecisionIgnoreCaseAndActor(any(), any(), any(), any());
    }

    @Test
    void listApprovalsRejectsLimitAboveRangeBeforeQuery() throws Exception {
        mockMvc.perform(get("/internal/ops/approvals")
                        .param("tenantId", UUID.randomUUID().toString())
                        .param("limit", "501"))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).findByTenantIdAndDecisionIgnoreCase(any(), any(), any());
        verify(approvalRepository, never()).findByTenantIdAndDecisionIgnoreCaseAndActor(any(), any(), any(), any());
    }

    @Test
    void listApprovalsRejectsNonNumericLimitBeforeQuery() throws Exception {
        mockMvc.perform(get("/internal/ops/approvals")
                        .param("tenantId", UUID.randomUUID().toString())
                        .param("limit", "abc"))
                .andExpect(status().is(ErrorCode.VALIDATION_FAILED.status().value()))
                .andExpect(jsonPath("$.code").value(code(ErrorCode.VALIDATION_FAILED)));

        verify(approvalRepository, never()).findByTenantIdAndDecisionIgnoreCase(any(), any(), any());
        verify(approvalRepository, never()).findByTenantIdAndDecisionIgnoreCaseAndActor(any(), any(), any(), any());
    }

    private static ApprovalEntity approval(UUID id, String decision, Instant expiresAt, Instant usedAt) {
        return approval(id, UUID.randomUUID(), decision, expiresAt, usedAt);
    }

    private static ApprovalEntity approval(UUID id, UUID tenantId, String decision, Instant expiresAt, Instant usedAt) {
        ApprovalEntity approval = new ApprovalEntity();
        approval.setId(id);
        approval.setTenantId(tenantId);
        approval.setActor("project_operator");
        approval.setOperation("restart_connector_task");
        approval.setParamsHash(PARAMS_HASH);
        approval.setDecision(decision);
        approval.setExpiresAt(expiresAt);
        approval.setUsedAt(usedAt);
        return approval;
    }

    private static void authenticate(UUID userId, UUID tenantId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, tenantId, "operator@bifrost.io");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static String code(ErrorCode code) {
        return String.valueOf(code.code());
    }
}
