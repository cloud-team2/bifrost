package com.bifrost.ops.workspace.kafka;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalCreateRequest;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/kafka/principals")
public class KafkaPrincipalController {

    private final KafkaPrincipalService principalService;

    public KafkaPrincipalController(KafkaPrincipalService principalService) {
        this.principalService = principalService;
    }

    @GetMapping
    public List<KafkaPrincipalResponse> list(@PathVariable UUID wsId,
                                             @AuthenticationPrincipal AuthenticatedUser principal) {
        return principalService.list(wsId, principal);
    }

    @PostMapping
    public ResponseEntity<KafkaPrincipalResponse> create(@PathVariable UUID wsId,
                                                         @AuthenticationPrincipal AuthenticatedUser principal,
                                                         @Valid @RequestBody KafkaPrincipalCreateRequest req) {
        return ResponseEntity.status(201).body(principalService.create(wsId, principal, req));
    }

    @PostMapping("/{id}/deactivate")
    public KafkaPrincipalResponse deactivate(@PathVariable UUID wsId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal AuthenticatedUser principal) {
        return principalService.deactivate(wsId, principal, id);
    }

    @PostMapping("/{id}/revoke")
    public KafkaPrincipalResponse revoke(@PathVariable UUID wsId,
                                         @PathVariable UUID id,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        return principalService.revoke(wsId, principal, id);
    }

    @PostMapping("/{id}/rotate")
    public KafkaPrincipalResponse rotate(@PathVariable UUID wsId,
                                         @PathVariable UUID id,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        return principalService.rotate(wsId, principal, id);
    }
}
