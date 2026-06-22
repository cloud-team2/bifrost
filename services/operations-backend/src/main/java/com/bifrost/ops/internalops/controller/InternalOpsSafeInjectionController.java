package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.safeinject.SafeInjectionCleanupResult;
import com.bifrost.ops.internalops.safeinject.SafeInjectionCreateRequest;
import com.bifrost.ops.internalops.safeinject.SafeInjectionCreateResult;
import com.bifrost.ops.internalops.safeinject.SafeInjectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ops/projects/{projectId}/safe-injection")
public class InternalOpsSafeInjectionController {

    private static final String OP_CREATE = "safe_inject_connector";
    private static final String OP_CLEANUP = "safe_inject_cleanup";
    private static final String OP_RESIDUALS = "safe_inject_residuals";

    private final SafeInjectionService service;

    public InternalOpsSafeInjectionController(SafeInjectionService service) {
        this.service = service;
    }

    @PostMapping("/connectors")
    public ResponseEntity<OpsEnvelope<SafeInjectionCreateResult>> create(
            @PathVariable String projectId,
            @RequestBody SafeInjectionCreateRequest body,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(OpsEnvelope.ok(requestId, OP_CREATE, service.create(projectId, body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(OpsEnvelope.error(requestId, OP_CREATE, "VALIDATION_FAILED", e.getMessage(), false));
        } catch (ApiException e) {
            return apiError(requestId, OP_CREATE, e);
        }
    }

    @DeleteMapping("/runs/{runId}")
    public ResponseEntity<OpsEnvelope<SafeInjectionCleanupResult>> cleanup(
            @PathVariable String projectId,
            @PathVariable String runId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, OP_CLEANUP, service.cleanup(projectId, runId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(OpsEnvelope.error(requestId, OP_CLEANUP, "VALIDATION_FAILED", e.getMessage(), false));
        } catch (ApiException e) {
            return apiError(requestId, OP_CLEANUP, e);
        }
    }

    @GetMapping("/runs/{runId}/residuals")
    public ResponseEntity<OpsEnvelope<SafeInjectionCleanupResult>> residuals(
            @PathVariable String projectId,
            @PathVariable String runId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, OP_RESIDUALS, service.residualsOnly(projectId, runId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(OpsEnvelope.error(requestId, OP_RESIDUALS, "VALIDATION_FAILED", e.getMessage(), false));
        } catch (ApiException e) {
            return apiError(requestId, OP_RESIDUALS, e);
        }
    }

    private static <T> ResponseEntity<OpsEnvelope<T>> apiError(String requestId, String operation, ApiException e) {
        return ResponseEntity.status(e.code().status())
                .body(OpsEnvelope.error(requestId, operation, e.code().name(), e.getMessage(), false,
                        "check_safe_injection_scope"));
    }
}
