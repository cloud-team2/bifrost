package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.connect.ConnectRestClient;
import com.bifrost.ops.adapters.connect.ConnectRestException;
import com.bifrost.ops.governance.MutationContext;
import com.bifrost.ops.governance.MutationGate;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard.CheckResult;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.ConnectorActionResult;
import com.bifrost.ops.internalops.dto.ConsumerGroupActionResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.operations.kafka.ConsumerGroupVerificationException;
import com.bifrost.ops.internalops.operations.kafka.ConsumerGroupVerifier;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalOpsMutationControllerTest {

    private static final String PROJECT_ID = "demo-project";
    private static final String CONNECTOR = "orders-sink";

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final PipelineRepository pipelineRepository = mock(PipelineRepository.class);
    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final MutationGate mutationGate = mock(MutationGate.class);
    private final IdempotencyGuard idempotencyGuard = mock(IdempotencyGuard.class);
    private final ConnectRestClient connectRestClient = mock(ConnectRestClient.class);
    private final ConsumerGroupVerifier consumerGroupVerifier = mock(ConsumerGroupVerifier.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InternalOpsMutationController controller = new InternalOpsMutationController(
            workspaceRepository,
            pipelineRepository,
            connectorRepository,
            mutationGate,
            idempotencyGuard,
            connectRestClient,
            consumerGroupVerifier,
            objectMapper);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();

    @BeforeEach
    void passThroughMutationGate() {
        when(mutationGate.executeChecked(any(MutationContext.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
    }

    @Test
    void missingMutationHeaderReturns400BeforeOwnershipOrConnect() {
        MockHttpServletRequest request = requestWithoutHeader(AgentHeaders.X_IDEMPOTENCY_KEY);

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(workspaceRepository, connectorRepository, pipelineRepository,
                mutationGate, idempotencyGuard, connectRestClient);
    }

    @Test
    void missingRunIdHeaderReturns400BeforeOwnershipOrConnect() {
        MockHttpServletRequest request = requestWithoutHeader(AgentHeaders.X_AGENT_RUN_ID);

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().message()).contains(AgentHeaders.X_AGENT_RUN_ID);
        verifyNoInteractions(workspaceRepository, connectorRepository, pipelineRepository,
                mutationGate, idempotencyGuard, connectRestClient);
    }

    @Test
    void missingStepIdHeaderReturns400BeforeOwnershipOrConnect() {
        MockHttpServletRequest request = requestWithoutHeader(AgentHeaders.X_AGENT_STEP_ID);

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().message()).contains(AgentHeaders.X_AGENT_STEP_ID);
        verifyNoInteractions(workspaceRepository, connectorRepository, pipelineRepository,
                mutationGate, idempotencyGuard, connectRestClient);
    }

    @Test
    void gateRejectionReleasesIdempotencyReservationBeforeConnect() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("pause_connector"), any()))
                .thenReturn(CheckResult.created());
        when(mutationGate.executeChecked(any(MutationContext.class), any()))
                .thenThrow(new com.bifrost.ops.global.common.error.ApiException(
                        com.bifrost.ops.global.common.error.ErrorCode.APPROVAL_NOT_FOUND,
                        "approval is required"));

        MockHttpServletRequest request = requestWithoutIdempotency();
        request.addHeader(AgentHeaders.X_IDEMPOTENCY_KEY, "idem-1");

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.pauseConnector(PROJECT_ID, CONNECTOR, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("APPROVAL_REQUIRED");
        verify(idempotencyGuard).check(eq("idem-1"), eq(tenantId), eq("pause_connector"), any());
        verify(idempotencyGuard).abandon("idem-1", tenantId);
        verify(mutationGate).executeChecked(any(MutationContext.class), any());
        verifyNoInteractions(connectRestClient);
    }

    @Test
    void mutationCanPassThroughGateWithoutApprovalWhenPolicyAllows() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("pause_connector"), any()))
                .thenReturn(CheckResult.created());
        MockHttpServletRequest request = requestWithoutIdempotency();
        request.addHeader(AgentHeaders.X_IDEMPOTENCY_KEY, "idem-1");

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.pauseConnector(PROJECT_ID, CONNECTOR, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(mutationGate).executeChecked(any(MutationContext.class), any());
        verify(connectRestClient).pauseConnector(CONNECTOR);
        verify(idempotencyGuard).complete(eq("idem-1"), eq(tenantId), any(),
                eq(IdempotencyGuard.RESPONSE_OK), eq(HttpStatus.OK.value()), isNull(), isNull());
    }

    @Test
    void governanceGateFailureAbandonsIdempotencyAndSkipsConnect() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("resume_connector"), any()))
                .thenReturn(CheckResult.created());
        when(mutationGate.executeChecked(any(MutationContext.class), any()))
                .thenThrow(new com.bifrost.ops.global.common.error.ApiException(
                        com.bifrost.ops.global.common.error.ErrorCode.WORKSPACE_FORBIDDEN,
                        "approval params_hash mismatch"));

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.resumeConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("APPROVAL_SCOPE_MISMATCH");
        verify(idempotencyGuard).abandon("idem-1", tenantId);
        verifyNoInteractions(connectRestClient);
    }

    @Test
    void idempotentDuplicateDoesNotConsumeApprovalOrCallConnectAgain() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_connector"), any()))
                .thenReturn(CheckResult.created())
                .thenReturn(CheckResult.duplicate("""
                        {"connector_name":"orders-sink","action":"restart_connector","status":"SUCCESS","message":"accepted"}
                        """, IdempotencyGuard.RESPONSE_OK, 200, approvalId));
        doNothing().when(connectRestClient).restartConnector(CONNECTOR);
        MockHttpServletRequest replayRequest = requestWithoutIdempotency();
        replayRequest.addHeader(AgentHeaders.X_IDEMPOTENCY_KEY, "idem-1");
        replayRequest.addHeader(AgentHeaders.X_APPROVAL_ID, approvalId.toString());

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> first =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request());
        ResponseEntity<OpsEnvelope<ConnectorActionResult>> second =
                controller.restartConnector(PROJECT_ID, CONNECTOR, replayRequest);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().result().connectorName()).isEqualTo(CONNECTOR);
        verify(connectRestClient).restartConnector(CONNECTOR);
        verify(mutationGate).executeChecked(any(MutationContext.class), any());
    }

    @Test
    void idempotentReplayRejectsMissingApprovalIdProof() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_connector"), any()))
                .thenReturn(CheckResult.duplicate("""
                        {"connector_name":"orders-sink","action":"restart_connector","status":"SUCCESS","message":"accepted"}
                        """, IdempotencyGuard.RESPONSE_OK, 200, approvalId));
        MockHttpServletRequest replayRequest = requestWithoutIdempotency();
        replayRequest.addHeader(AgentHeaders.X_IDEMPOTENCY_KEY, "idem-1");

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, replayRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("APPROVAL_SCOPE_MISMATCH");
        verifyNoInteractions(mutationGate, connectRestClient);
    }

    @Test
    void idempotentReplayRequiresSameApprovalIdProof() {
        stubOwnedConnector();
        UUID otherApproval = UUID.randomUUID();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_connector"), any()))
                .thenReturn(CheckResult.duplicate("""
                        {"connector_name":"orders-sink","action":"restart_connector","status":"SUCCESS","message":"accepted"}
                        """, IdempotencyGuard.RESPONSE_OK, 200, otherApproval));

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("APPROVAL_SCOPE_MISMATCH");
        verifyNoInteractions(mutationGate, connectRestClient);
    }

    @Test
    void idempotentReplayRequiresSameChangeTicketProof() {
        stubOwnedConnector();
        UUID changeTicketId = UUID.randomUUID();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_connector"), any()))
                .thenReturn(CheckResult.duplicate("""
                        {"connector_name":"orders-sink","action":"restart_connector","status":"SUCCESS","message":"accepted"}
                        """, IdempotencyGuard.RESPONSE_OK, 200, approvalId, changeTicketId));

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("CHANGE_TICKET_REQUIRED");
        verifyNoInteractions(mutationGate, connectRestClient);
    }

    @Test
    void idempotentConnectorReplayReturnsCachedEnvelopeWithoutMutation() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_connector"), any()))
                .thenReturn(CheckResult.duplicate("""
                        {"connector_name":"orders-sink","action":"restart_connector","status":"SUCCESS","message":"accepted"}
                        """, IdempotencyGuard.RESPONSE_OK, 200, approvalId));

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().result().connectorName()).isEqualTo(CONNECTOR);
        verifyNoInteractions(mutationGate, connectRestClient);
    }

    @Test
    void idempotentConsumerGroupReplayReturnsCachedEnvelopeWithoutMutation() {
        stubOwnedConnector();
        doNothing().when(consumerGroupVerifier).requireExists("connect-" + CONNECTOR);
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_consumer_group"), any()))
                .thenReturn(CheckResult.duplicate("""
                        {"consumer_group":"connect-orders-sink","action":"restart_consumer_group","status":"SUCCESS","message":"accepted"}
                        """, IdempotencyGuard.RESPONSE_OK, 200, approvalId));

        ResponseEntity<OpsEnvelope<ConsumerGroupActionResult>> response =
                controller.restartConsumerGroup(PROJECT_ID, "connect-" + CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().result().consumerGroup()).isEqualTo("connect-" + CONNECTOR);
        verifyNoInteractions(mutationGate, connectRestClient);
    }

    @Test
    void idempotencyConflictRejectsDifferentOperationParametersBeforeApproval() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_connector"), any()))
                .thenReturn(CheckResult.conflict());

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.restartConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("CONFLICT");
        verifyNoInteractions(mutationGate, connectRestClient);
    }

    @Test
    void failedConnectMutationReplaysAsFailedEnvelope() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("pause_connector"), any()))
                .thenReturn(CheckResult.created())
                .thenReturn(CheckResult.duplicate("""
                        {"code":"UPSTREAM_UNAVAILABLE","message":"Kafka Connect REST failed during pause_connector","retryable":false,"required_action":"get_connector_status"}
                        """, IdempotencyGuard.RESPONSE_ERROR, HttpStatus.BAD_GATEWAY.value(), approvalId));
        doThrow(ConnectRestException.upstream("pause_connector", 500, new RuntimeException("boom")))
                .when(connectRestClient).pauseConnector(CONNECTOR);

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> first =
                controller.pauseConnector(PROJECT_ID, CONNECTOR, request());
        ResponseEntity<OpsEnvelope<ConnectorActionResult>> replay =
                controller.pauseConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().ok()).isFalse();
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().ok()).isFalse();
        assertThat(replay.getBody().error().code()).isEqualTo("UPSTREAM_UNAVAILABLE");
        verify(connectRestClient).pauseConnector(CONNECTOR);
    }

    @Test
    void connectorOutsideProjectIsRejectedBeforeApprovalOrConnect() {
        WorkspaceEntity workspace = workspace();
        ConnectorEntity connector = connector();
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace));
        when(connectorRepository.findByCrName(CONNECTOR)).thenReturn(Optional.of(connector));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.resumeConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_OWNED_BY_PROJECT");
        verifyNoInteractions(mutationGate, idempotencyGuard, connectRestClient);
    }

    @Test
    void approvedPauseCallsKafkaConnectRestClient() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("pause_connector"), any()))
                .thenReturn(CheckResult.created());
        doNothing().when(connectRestClient).pauseConnector(CONNECTOR);

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.pauseConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().result().action()).isEqualTo("pause_connector");
        verify(connectRestClient).pauseConnector(CONNECTOR);
        verify(mutationGate).executeChecked(any(MutationContext.class), any());
        verify(idempotencyGuard).complete(eq("idem-1"), eq(tenantId), any(),
                eq(IdempotencyGuard.RESPONSE_OK), eq(HttpStatus.OK.value()), eq(approvalId), isNull());
    }

    @Test
    void approvedMutationPassesAuditAndChangeTicketContextToGate() {
        stubOwnedConnector();
        UUID changeTicketId = UUID.randomUUID();
        MockHttpServletRequest request = request();
        request.addHeader(AgentHeaders.X_ACTOR_ID, "actor-1");
        request.addHeader(AgentHeaders.X_CHANGE_TICKET_ID, changeTicketId.toString());
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("pause_connector"), any()))
                .thenReturn(CheckResult.created());

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.pauseConnector(PROJECT_ID, CONNECTOR, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<MutationContext> contextCaptor = ArgumentCaptor.forClass(MutationContext.class);
        verify(mutationGate).executeChecked(contextCaptor.capture(), any());
        MutationContext ctx = contextCaptor.getValue();
        assertThat(ctx.tenantId()).isEqualTo(tenantId);
        assertThat(ctx.actor()).isEqualTo("actor-1");
        assertThat(ctx.operation()).isEqualTo("pause_connector");
        assertThat(ctx.targetType()).isEqualTo("CONNECTOR");
        assertThat(ctx.targetId()).isNotNull();
        assertThat(ctx.idempotencyKey()).isEqualTo("idem-1");
        assertThat(ctx.approvalId()).isEqualTo(approvalId);
        assertThat(ctx.changeTicketId()).isEqualTo(changeTicketId);
        assertThat(ctx.paramsHash()).hasSize(64);
        assertThat(ctx.beforeSnapshot())
                .containsEntry("project_id", PROJECT_ID)
                .containsEntry("connector_name", CONNECTOR);
        verify(idempotencyGuard).complete(eq("idem-1"), eq(tenantId), any(),
                eq(IdempotencyGuard.RESPONSE_OK), eq(HttpStatus.OK.value()),
                eq(approvalId), eq(changeTicketId));
    }

    @Test
    void approvedResumeCallsKafkaConnectRestClient() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("resume_connector"), any()))
                .thenReturn(CheckResult.created());

        ResponseEntity<OpsEnvelope<ConnectorActionResult>> response =
                controller.resumeConnector(PROJECT_ID, CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().result().action()).isEqualTo("resume_connector");
        verify(connectRestClient).resumeConnector(CONNECTOR);
        verify(idempotencyGuard).complete(eq("idem-1"), eq(tenantId), any(),
                eq(IdempotencyGuard.RESPONSE_OK), eq(HttpStatus.OK.value()), eq(approvalId), isNull());
    }

    @Test
    void restartConsumerGroupMapsConnectGroupToOwnedConnector() {
        stubOwnedConnector();
        when(idempotencyGuard.check(eq("idem-1"), eq(tenantId), eq("restart_consumer_group"), any()))
                .thenReturn(CheckResult.created());
        var response = controller.restartConsumerGroup(PROJECT_ID, "connect-" + CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().result().consumerGroup()).isEqualTo("connect-" + CONNECTOR);
        verify(consumerGroupVerifier).requireExists("connect-" + CONNECTOR);
        verify(connectRestClient).restartConnector(CONNECTOR);
    }

    @Test
    void restartConsumerGroupRejectsMissingKafkaGroupBeforeApproval() {
        stubOwnedConnector();
        doThrow(ConsumerGroupVerificationException.notFound("connect-" + CONNECTOR))
                .when(consumerGroupVerifier).requireExists("connect-" + CONNECTOR);

        var response = controller.restartConsumerGroup(PROJECT_ID, "connect-" + CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("CONSUMER_GROUP_NOT_FOUND");
        verifyNoInteractions(mutationGate, idempotencyGuard, connectRestClient);
    }

    @Test
    void restartConsumerGroupRejectsSourceConnectorGroup() {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        ConnectorEntity source = connector();
        source.setKind(ConnectorKind.SOURCE);
        when(connectorRepository.findByCrName(CONNECTOR)).thenReturn(Optional.of(source));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline()));

        var response = controller.restartConsumerGroup(PROJECT_ID, "connect-" + CONNECTOR, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("CONSUMER_GROUP_NOT_FOUND");
        verifyNoInteractions(mutationGate, idempotencyGuard, connectRestClient);
    }

    private void stubOwnedConnector() {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        when(connectorRepository.findByCrName(CONNECTOR)).thenReturn(Optional.of(connector()));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline()));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = requestWithoutIdempotency();
        request.addHeader(AgentHeaders.X_IDEMPOTENCY_KEY, "idem-1");
        request.addHeader(AgentHeaders.X_APPROVAL_ID, approvalId.toString());
        return request;
    }

    private MockHttpServletRequest requestWithoutIdempotency() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AgentHeaders.X_AGENT_RUN_ID, "run-1");
        request.addHeader(AgentHeaders.X_AGENT_STEP_ID, "step-1");
        request.addHeader(AgentHeaders.X_REQUEST_ID, "req-1");
        return request;
    }

    private MockHttpServletRequest requestWithoutHeader(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (!AgentHeaders.X_AGENT_RUN_ID.equals(header)) {
            request.addHeader(AgentHeaders.X_AGENT_RUN_ID, "run-1");
        }
        if (!AgentHeaders.X_AGENT_STEP_ID.equals(header)) {
            request.addHeader(AgentHeaders.X_AGENT_STEP_ID, "step-1");
        }
        if (!AgentHeaders.X_IDEMPOTENCY_KEY.equals(header)) {
            request.addHeader(AgentHeaders.X_IDEMPOTENCY_KEY, "idem-1");
        }
        request.addHeader(AgentHeaders.X_REQUEST_ID, "req-1");
        return request;
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(tenantId);
        workspace.setName("Demo");
        workspace.setNamespace(PROJECT_ID);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }

    private PipelineEntity pipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setTenantId(tenantId);
        pipeline.setName("orders");
        pipeline.setStatus(PipelineLifecycle.ACTIVE);
        return pipeline;
    }

    private ConnectorEntity connector() {
        ConnectorEntity connector = new ConnectorEntity();
        connector.setId(UUID.randomUUID());
        connector.setPipelineId(pipelineId);
        connector.setCrName(CONNECTOR);
        connector.setKind(ConnectorKind.SINK);
        connector.setConnectorClass("io.confluent.connect.jdbc.JdbcSinkConnector");
        connector.setTasksMax(1);
        return connector;
    }

}
