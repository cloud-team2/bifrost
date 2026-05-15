package com.platform.orchestrator.api.controller;

import com.platform.common.orchestrator.PipelineCreateRequest;
import com.platform.common.orchestrator.PipelineCreateResponse;
import com.platform.common.orchestrator.TenantProvisionRequest;
import com.platform.common.orchestrator.TenantProvisionResponse;
import com.platform.orchestrator.kafka.connector.ConnectorManager;
import com.platform.orchestrator.kafka.tenant.TenantProvisioner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal")
public class InternalController {

    private final TenantProvisioner tenantProvisioner;
    private final ConnectorManager connectorManager;

    public InternalController(TenantProvisioner tenantProvisioner,
                              ConnectorManager connectorManager) {
        this.tenantProvisioner = tenantProvisioner;
        this.connectorManager = connectorManager;
    }

    @PostMapping("/tenants/provision")
    public ResponseEntity<TenantProvisionResponse> provisionTenant(
            @RequestBody TenantProvisionRequest req) {
        return ResponseEntity.ok(tenantProvisioner.provision(req));
    }

    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<Void> deprovisionTenant(@PathVariable UUID tenantId) {
        tenantProvisioner.deprovision(tenantId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/pipelines")
    public ResponseEntity<PipelineCreateResponse> createPipeline(
            @RequestBody PipelineCreateRequest req) {
        return ResponseEntity.accepted().body(
            connectorManager.createSourceConnector(req)
        );
    }

    @GetMapping("/pipelines/{pipelineId}/status")
    public ResponseEntity<String> getStatus(@PathVariable UUID pipelineId,
                                            @RequestParam String namespace,
                                            @RequestParam String connectorName) {
        return ResponseEntity.ok(connectorManager.getStatus(pipelineId, namespace, connectorName));
    }

    @DeleteMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Void> deletePipeline(@PathVariable UUID pipelineId,
                                               @RequestParam String namespace,
                                               @RequestParam String connectorName) {
        connectorManager.delete(pipelineId, namespace, connectorName);
        return ResponseEntity.accepted().build();
    }
}
