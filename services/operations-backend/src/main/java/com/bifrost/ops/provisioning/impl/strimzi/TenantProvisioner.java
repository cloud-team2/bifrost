package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 회원가입 시 자동으로 K8s 리소스 생성:
 * - Namespace
 * - KafkaUser (Strimzi가 Secret 자동 생성)
 * - ResourceQuota
 * - NetworkPolicy
 * - RoleBinding
 *
 * Idempotent: 이미 있으면 200 OK 반환.
 */
@Component
public class TenantProvisioner {

    private final KubernetesClient k8s;
    private final String kafkaClusterName;

    public TenantProvisioner(KubernetesClient k8s,
                             @Value("${kafka-cluster.name}") String kafkaClusterName) {
        this.k8s = k8s;
        this.kafkaClusterName = kafkaClusterName;
    }

    public TenantProvisionResponse provision(TenantProvisionRequest req) {
        // TODO: 순서대로 생성
        // 1. createNamespace
        // 2. createResourceQuota
        // 3. createNetworkPolicy
        // 4. createKafkaUser
        // 5. waitForKafkaUserSecret
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void deprovision(java.util.UUID tenantId) {
        // TODO: Namespace 삭제 (cascade)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    // ---- private helpers ----
    private void createNamespace(String name, java.util.UUID tenantId) {
        // TODO
    }

    private void createKafkaUser(String namespace, String userName, java.util.UUID tenantId) {
        // TODO: Strimzi KafkaUser CRD 생성 (SCRAM + ACL with tenant prefix)
    }

    private void createResourceQuota(String namespace, TenantProvisionRequest.ResourceQuotaSpec spec) {
        // TODO
    }

    private void createNetworkPolicy(String namespace) {
        // TODO
    }
}
