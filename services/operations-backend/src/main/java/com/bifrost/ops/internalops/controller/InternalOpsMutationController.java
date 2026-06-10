package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.connect.ConnectRestClient;
import com.bifrost.ops.adapters.connect.ConnectRestException;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.MutationContext;
import com.bifrost.ops.governance.MutationGate;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard.CheckKind;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard.CheckResult;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.ConnectorActionResult;
import com.bifrost.ops.internalops.dto.ConsumerGroupActionResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.OpsError;
import com.bifrost.ops.internalops.operations.kafka.ConsumerGroupVerificationException;
import com.bifrost.ops.internalops.operations.kafka.ConsumerGroupVerifier;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Internal Ops mutation controller (#308).
 *
 * <p>모든 mutation은 header → project scope → idempotency → project ownership → governance gate →
 * Kafka Connect REST 순서로 처리한다. InternalOpsController tool catalog는 #309 소관이라 수정하지 않는다.
 */
@RestController
@RequestMapping("/internal/ops/projects/{projectId}")
public class InternalOpsMutationController {

    private static final Logger log = LoggerFactory.getLogger(InternalOpsMutationController.class);

    private static final String OP_RESTART_CONNECTOR = "restart_connector";
    private static final String OP_PAUSE_CONNECTOR = "pause_connector";
    private static final String OP_RESUME_CONNECTOR = "resume_connector";
    private static final String OP_RESTART_CONSUMER_GROUP = "restart_consumer_group";

    private static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String CODE_APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    private static final String CODE_APPROVAL_EXPIRED = "APPROVAL_EXPIRED";
    private static final String CODE_APPROVAL_SCOPE_MISMATCH = "APPROVAL_SCOPE_MISMATCH";
    private static final String CODE_CHANGE_TICKET_REQUIRED = "CHANGE_TICKET_REQUIRED";
    private static final String CODE_CHANGE_WINDOW_CLOSED = "CHANGE_WINDOW_CLOSED";
    private static final String CODE_CHANGE_SCOPE_MISMATCH = "CHANGE_SCOPE_MISMATCH";
    private static final String CODE_CONFLICT = "CONFLICT";
    private static final String CODE_CONNECTOR_NOT_FOUND = "CONNECTOR_NOT_FOUND";
    private static final String CODE_CONSUMER_GROUP_NOT_FOUND = "CONSUMER_GROUP_NOT_FOUND";
    private static final String CODE_POLICY_DENIED = "POLICY_DENIED";
    private static final String CODE_RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    private static final String CODE_RESOURCE_NOT_OWNED_BY_PROJECT = "RESOURCE_NOT_OWNED_BY_PROJECT";
    private static final String CODE_TIMEOUT = "TIMEOUT";
    private static final String CODE_UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final MutationGate mutationGate;
    private final IdempotencyGuard idempotencyGuard;
    private final ConnectRestClient connectRestClient;
    private final ConsumerGroupVerifier consumerGroupVerifier;
    private final ObjectMapper objectMapper;

    public InternalOpsMutationController(WorkspaceRepository workspaceRepository,
                                         PipelineRepository pipelineRepository,
                                         ConnectorRepository connectorRepository,
                                         MutationGate mutationGate,
                                         IdempotencyGuard idempotencyGuard,
                                         ConnectRestClient connectRestClient,
                                         ConsumerGroupVerifier consumerGroupVerifier,
                                         ObjectMapper objectMapper) {
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.mutationGate = mutationGate;
        this.idempotencyGuard = idempotencyGuard;
        this.connectRestClient = connectRestClient;
        this.consumerGroupVerifier = consumerGroupVerifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/connectors/{connectorName}/restart")
    public ResponseEntity<OpsEnvelope<ConnectorActionResult>> restartConnector(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        return connectorMutation(projectId, connectorName, request, OP_RESTART_CONNECTOR,
                connectRestClient::restartConnector);
    }

    @PostMapping("/connectors/{connectorName}/pause")
    public ResponseEntity<OpsEnvelope<ConnectorActionResult>> pauseConnector(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        return connectorMutation(projectId, connectorName, request, OP_PAUSE_CONNECTOR,
                connectRestClient::pauseConnector);
    }

    @PostMapping("/connectors/{connectorName}/resume")
    public ResponseEntity<OpsEnvelope<ConnectorActionResult>> resumeConnector(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        return connectorMutation(projectId, connectorName, request, OP_RESUME_CONNECTOR,
                connectRestClient::resumeConnector);
    }

    @PostMapping("/kafka/consumer-groups/{consumerGroup}/restart")
    public ResponseEntity<OpsEnvelope<ConsumerGroupActionResult>> restartConsumerGroup(
            @PathVariable String projectId,
            @PathVariable String consumerGroup,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        Optional<ResponseEntity<OpsEnvelope<ConsumerGroupActionResult>>> headerError =
                mutationHeaderError(request, requestId, OP_RESTART_CONSUMER_GROUP);
        if (headerError.isPresent()) {
            return headerError.get();
        }

        WorkspaceEntity workspace;
        try {
            workspace = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, OP_RESTART_CONSUMER_GROUP, e, CODE_CONSUMER_GROUP_NOT_FOUND);
        }
        OwnedConnector owned;
        try {
            owned = requireOwnedConnectConsumerGroup(workspace, consumerGroup);
            consumerGroupVerifier.requireExists(consumerGroup);
        } catch (ApiException e) {
            return apiError(requestId, OP_RESTART_CONSUMER_GROUP, e, CODE_CONSUMER_GROUP_NOT_FOUND);
        } catch (ConsumerGroupVerificationException e) {
            HttpStatus status = e.isUnavailable() ? HttpStatus.BAD_GATEWAY : HttpStatus.NOT_FOUND;
            String code = e.isUnavailable() ? CODE_UPSTREAM_UNAVAILABLE : CODE_CONSUMER_GROUP_NOT_FOUND;
            return ResponseEntity.status(status)
                    .body(OpsEnvelope.error(requestId, OP_RESTART_CONSUMER_GROUP,
                            code, e.getMessage(), false, "get_consumer_lag"));
        }

        String idempotencyKey = AgentHeaders.header(request, AgentHeaders.X_IDEMPOTENCY_KEY);
        String paramsHash = paramsHash(OP_RESTART_CONSUMER_GROUP, projectId,
                "consumer_group", consumerGroup);
        CheckResult idempotency = idempotencyGuard.check(
                idempotencyKey, workspace.getId(), OP_RESTART_CONSUMER_GROUP, paramsHash);
        Optional<ResponseEntity<OpsEnvelope<ConsumerGroupActionResult>>> replay =
                idempotencyResponse(
                        requestId,
                        OP_RESTART_CONSUMER_GROUP,
                        idempotency,
                        request,
                        ConsumerGroupActionResult.class,
                        () -> ConsumerGroupActionResult.replay(consumerGroup, OP_RESTART_CONSUMER_GROUP));
        if (replay.isPresent()) {
            return replay.get();
        }

        return executeNewMutation(
                requestId,
                OP_RESTART_CONSUMER_GROUP,
                idempotencyKey,
                workspace.getId(),
                paramsHash,
                request,
                "CONSUMER_GROUP",
                owned.connector().getId(),
                Map.of(
                        "project_id", projectId,
                        "consumer_group", consumerGroup,
                        "connector_name", owned.connector().getCrName()),
                () -> ConsumerGroupActionResult.accepted(consumerGroup, OP_RESTART_CONSUMER_GROUP),
                () -> connectRestClient.restartConnector(owned.connector().getCrName()),
                "get_consumer_lag");
    }

    private ResponseEntity<OpsEnvelope<ConnectorActionResult>> connectorMutation(
            String projectId,
            String connectorName,
            HttpServletRequest request,
            String operation,
            ConnectorCall call) {
        String requestId = AgentHeaders.requestId(request);
        Optional<ResponseEntity<OpsEnvelope<ConnectorActionResult>>> headerError =
                mutationHeaderError(request, requestId, operation);
        if (headerError.isPresent()) {
            return headerError.get();
        }

        WorkspaceEntity workspace;
        try {
            workspace = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, operation, e, CODE_CONNECTOR_NOT_FOUND);
        }

        OwnedConnector owned;
        try {
            owned = requireOwnedConnector(workspace, connectorName);
        } catch (ApiException e) {
            return apiError(requestId, operation, e, CODE_CONNECTOR_NOT_FOUND);
        }

        String idempotencyKey = AgentHeaders.header(request, AgentHeaders.X_IDEMPOTENCY_KEY);
        String paramsHash = paramsHash(operation, projectId, "connector_name", connectorName);
        CheckResult idempotency = idempotencyGuard.check(idempotencyKey, workspace.getId(), operation, paramsHash);
        Optional<ResponseEntity<OpsEnvelope<ConnectorActionResult>>> replay =
                idempotencyResponse(
                        requestId,
                        operation,
                        idempotency,
                        request,
                        ConnectorActionResult.class,
                        () -> ConnectorActionResult.replay(connectorName, operation));
        if (replay.isPresent()) {
            return replay.get();
        }

        return executeNewMutation(
                requestId,
                operation,
                idempotencyKey,
                workspace.getId(),
                paramsHash,
                request,
                "CONNECTOR",
                owned.connector().getId(),
                Map.of(
                        "project_id", projectId,
                        "connector_name", connectorName,
                        "connector_id", String.valueOf(owned.connector().getId())),
                () -> ConnectorActionResult.accepted(connectorName, operation),
                () -> call.apply(connectorName),
                "get_connector_status");
    }

    private <T> Optional<ResponseEntity<OpsEnvelope<T>>> idempotencyResponse(
            String requestId,
            String operation,
            CheckResult idempotency,
            HttpServletRequest request,
            Class<T> resultClass,
            Supplier<T> replayResult) {
        if (idempotency.kind() == CheckKind.NEW) {
            return Optional.empty();
        }
        if (idempotency.kind() == CheckKind.CONFLICT) {
            return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(OpsEnvelope.error(requestId, operation, CODE_CONFLICT,
                            "idempotency key was already used with different operation parameters",
                            false, null)));
        }
        if (idempotency.kind() == CheckKind.PROCESSING) {
            return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(OpsEnvelope.error(requestId, operation, CODE_CONFLICT,
                            "idempotency key is already processing", false, null)));
        }
        UUID replayApprovalId;
        try {
            replayApprovalId = approvalId(request);
        } catch (IllegalArgumentException e) {
            return Optional.of(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OpsEnvelope.error(requestId, operation, CODE_VALIDATION_FAILED, e.getMessage())));
        }
        UUID replayChangeTicketId;
        try {
            replayChangeTicketId = changeTicketId(request);
        } catch (IllegalArgumentException e) {
            return Optional.of(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OpsEnvelope.error(requestId, operation, CODE_VALIDATION_FAILED, e.getMessage())));
        }
        if (!sameNullable(idempotency.approvalId(), replayApprovalId)) {
            return Optional.of(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(OpsEnvelope.error(requestId, operation, CODE_APPROVAL_SCOPE_MISMATCH,
                            "idempotency replay approval mismatch", false, "request_approval")));
        }
        if (!sameNullable(idempotency.changeTicketId(), replayChangeTicketId)) {
            return Optional.of(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(OpsEnvelope.error(requestId, operation, CODE_CHANGE_TICKET_REQUIRED,
                            "idempotency replay change ticket mismatch", false, "request_change_ticket")));
        }
        return Optional.of(replayCachedEnvelope(requestId, operation, idempotency, resultClass, replayResult));
    }

    private <T> ResponseEntity<OpsEnvelope<T>> executeNewMutation(
            String requestId,
            String operation,
            String idempotencyKey,
            UUID tenantId,
            String paramsHash,
            HttpServletRequest request,
            String targetType,
            UUID targetId,
            Map<String, Object> beforeSnapshot,
            Supplier<T> acceptedResult,
            Runnable mutation,
            String failureRequiredAction) {

        UUID approvalId;
        try {
            approvalId = approvalId(request);
        } catch (IllegalArgumentException e) {
            idempotencyGuard.abandon(idempotencyKey, tenantId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OpsEnvelope.error(requestId, operation, CODE_VALIDATION_FAILED, e.getMessage()));
        }
        UUID changeTicketId;
        try {
            changeTicketId = changeTicketId(request);
        } catch (IllegalArgumentException e) {
            idempotencyGuard.abandon(idempotencyKey, tenantId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OpsEnvelope.error(requestId, operation, CODE_VALIDATION_FAILED, e.getMessage()));
        }

        MutationContext ctx = new MutationContext(
                tenantId,
                actor(request),
                operation,
                targetType,
                targetId,
                idempotencyKey,
                approvalId,
                changeTicketId,
                paramsHash,
                beforeSnapshot);

        try {
            T result = mutationGate.executeChecked(ctx, () -> {
                mutation.run();
                return acceptedResult.get();
            });
            idempotencyGuard.complete(idempotencyKey, tenantId, toJson(result),
                    IdempotencyGuard.RESPONSE_OK, HttpStatus.OK.value(), approvalId, changeTicketId);
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, operation, result));
        } catch (ApiException e) {
            idempotencyGuard.abandon(idempotencyKey, tenantId);
            GovernanceError governanceError = governanceError(e);
            return ResponseEntity.status(governanceError.httpStatus())
                    .body(OpsEnvelope.error(requestId, operation, governanceError.code(),
                            governanceError.message(), false, governanceError.requiredAction()));
        } catch (ConnectRestException e) {
            log.warn("[InternalOpsMutation] Connect REST mutation failed: op={} timeout={} status={} cause={}",
                    operation, e.isTimeout(), e.statusCode(), safeMessage(e));
            HttpStatus status = e.isTimeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            String code = e.isTimeout() ? CODE_TIMEOUT : CODE_UPSTREAM_UNAVAILABLE;
            OpsError error = new OpsError(code, e.getMessage(), false, failureRequiredAction);
            idempotencyGuard.complete(idempotencyKey, tenantId, toJson(error),
                    IdempotencyGuard.RESPONSE_ERROR, status.value(), approvalId, changeTicketId);
            return ResponseEntity.status(status)
                    .body(OpsEnvelope.error(requestId, operation, code, e.getMessage(),
                            false, failureRequiredAction));
        }
    }

    private OwnedConnector requireOwnedConnectConsumerGroup(WorkspaceEntity workspace, String consumerGroup) {
        if (!consumerGroup.startsWith("connect-") || consumerGroup.length() <= "connect-".length()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "restart_consumer_group supports Kafka Connect-managed groups only");
        }
        OwnedConnector owned = requireOwnedConnector(workspace, consumerGroup.substring("connect-".length()));
        if (owned.connector().getKind() != ConnectorKind.SINK) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "consumer group is not backed by a sink connector: " + consumerGroup);
        }
        return owned;
    }

    private WorkspaceEntity requireWorkspace(String projectId) {
        return WorkspaceLookup.resolve(workspaceRepository, projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
    }

    private OwnedConnector requireOwnedConnector(WorkspaceEntity workspace, String connectorName) {
        ConnectorEntity connector = connectorRepository.findByCrName(connectorName)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "connector not found: " + connectorName));
        pipelineRepository.findByIdAndTenantId(connector.getPipelineId(), workspace.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_FORBIDDEN,
                        "connector is not owned by project: " + workspace.getNamespace()));
        return new OwnedConnector(workspace, connector);
    }

    private static <T> Optional<ResponseEntity<OpsEnvelope<T>>> mutationHeaderError(
            HttpServletRequest request,
            String requestId,
            String operation) {
        for (String header : new String[] {
                AgentHeaders.X_AGENT_RUN_ID,
                AgentHeaders.X_AGENT_STEP_ID,
                AgentHeaders.X_IDEMPOTENCY_KEY
        }) {
            if (AgentHeaders.header(request, header) == null) {
                return Optional.of(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(OpsEnvelope.error(requestId, operation, CODE_VALIDATION_FAILED,
                                "missing required header: " + header)));
            }
        }
        return Optional.empty();
    }

    private UUID approvalId(HttpServletRequest request) {
        String raw = AgentHeaders.header(request, AgentHeaders.X_APPROVAL_ID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid X-Approval-Id header");
        }
    }

    private UUID changeTicketId(HttpServletRequest request) {
        String raw = AgentHeaders.header(request, AgentHeaders.X_CHANGE_TICKET_ID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid X-Change-Ticket-Id header");
        }
    }

    private String actor(HttpServletRequest request) {
        String actor = AgentHeaders.header(request, AgentHeaders.X_AGENT_ID);
        if (actor != null) {
            return actor;
        }
        return AgentHeaders.header(request, AgentHeaders.X_ACTOR_ID);
    }

    private static boolean sameNullable(UUID stored, UUID supplied) {
        return stored == null ? supplied == null : stored.equals(supplied);
    }

    private <T> T replayCached(String cached, Class<T> resultClass, Supplier<T> fallback) {
        try {
            return objectMapper.readValue(cached, resultClass);
        } catch (Exception ignored) {
            return fallback.get();
        }
    }

    private String paramsHash(String operation, String projectId, String targetKey, String targetValue) {
        Map<String, Object> params = new TreeMap<>();
        params.put("project_id", projectId);
        params.put(targetKey, targetValue);
        params.put("tool_name", operation);
        try {
            byte[] json = objectMapper.writeValueAsBytes(params);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("params hash generation failed", e);
        }
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private <T> ResponseEntity<OpsEnvelope<T>> replayCachedEnvelope(String requestId,
                                                                    String operation,
                                                                    CheckResult idempotency,
                                                                    Class<T> resultClass,
                                                                    Supplier<T> fallback) {
        if (IdempotencyGuard.RESPONSE_ERROR.equals(idempotency.responseStatus())) {
            OpsError error = replayCachedError(idempotency.result());
            HttpStatus status = idempotency.httpStatus() != null
                    ? HttpStatus.valueOf(idempotency.httpStatus())
                    : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status)
                    .body(OpsEnvelope.error(requestId, operation, error.code(), error.message(),
                            error.retryable(), error.requiredAction()));
        }
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, operation,
                replayCached(idempotency.result(), resultClass, fallback)));
    }

    private OpsError replayCachedError(String cached) {
        try {
            return objectMapper.readValue(cached, OpsError.class);
        } catch (Exception ignored) {
            return new OpsError(CODE_UPSTREAM_UNAVAILABLE,
                    "previous mutation attempt failed", false, null);
        }
    }

    private static <T> ResponseEntity<OpsEnvelope<T>> apiError(
            String requestId,
            String operation,
            ApiException e,
            String notFoundCode) {
        if (e.code() == ErrorCode.WORKSPACE_FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(OpsEnvelope.error(requestId, operation, CODE_RESOURCE_NOT_OWNED_BY_PROJECT,
                            e.getMessage(), false, "check_project_scope"));
        }
        if (e.code() == ErrorCode.WORKSPACE_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(OpsEnvelope.error(requestId, operation, CODE_RESOURCE_NOT_FOUND,
                            e.getMessage(), false, "check_project_scope"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpsEnvelope.error(requestId, operation, notFoundCode,
                        e.getMessage(), false, "check_project_scope"));
    }

    private static GovernanceError governanceError(ApiException e) {
        if (e.code() == ErrorCode.CHANGE_WINDOW_CLOSED) {
            String message = e.getMessage() != null ? e.getMessage() : "change ticket outside execution window";
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_CHANGE_WINDOW_CLOSED,
                    message, "wait_for_change_window");
        }
        if (e.code() == ErrorCode.CHANGE_SCOPE_MISMATCH) {
            String message = e.getMessage() != null ? e.getMessage() : "change ticket operation scope mismatch";
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_CHANGE_SCOPE_MISMATCH,
                    message, "request_change_ticket");
        }
        if (e.code() == ErrorCode.CHANGE_TICKET_REQUIRED || e.code() == ErrorCode.CHANGE_TICKET_NOT_FOUND) {
            String message = e.getMessage() != null ? e.getMessage() : "change ticket validation failed";
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_CHANGE_TICKET_REQUIRED,
                    message, "request_change_ticket");
        }
        if (e.code() == ErrorCode.VALIDATION_FAILED) {
            String message = e.getMessage() != null ? e.getMessage() : "operation denied by policy";
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_POLICY_DENIED,
                    message, null);
        }
        if (e.code() == ErrorCode.RESOURCE_NOT_FOUND) {
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_APPROVAL_REQUIRED,
                    "approval not found", "request_approval");
        }
        String message = e.getMessage() != null ? e.getMessage() : "approval validation failed";
        if (message.contains("expired")) {
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_APPROVAL_EXPIRED,
                    message, "request_approval");
        }
        if (message.contains("already used")) {
            return new GovernanceError(HttpStatus.CONFLICT, CODE_CONFLICT,
                    message, "request_approval");
        }
        if (message.contains("mismatch")) {
            return new GovernanceError(HttpStatus.FORBIDDEN, CODE_APPROVAL_SCOPE_MISMATCH,
                    message, "request_approval");
        }
        return new GovernanceError(HttpStatus.FORBIDDEN, CODE_APPROVAL_REQUIRED,
                message, "request_approval");
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record OwnedConnector(WorkspaceEntity workspace, ConnectorEntity connector) {}

    private record GovernanceError(HttpStatus httpStatus, String code, String message, String requiredAction) {}

    @FunctionalInterface
    private interface ConnectorCall {
        void apply(String connectorName);
    }
}
