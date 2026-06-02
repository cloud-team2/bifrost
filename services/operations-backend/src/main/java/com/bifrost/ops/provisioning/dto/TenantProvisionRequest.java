package com.bifrost.ops.provisioning.dto;

import java.util.UUID;

/**
 * core → orchestrator: 회원가입 시 테넌트 K8s 리소스 자동 생성 요청.
 * 
 * orchestrator가 다음을 생성:
 * - Namespace
 * - KafkaUser (SCRAM + ACL)
 * - ResourceQuota
 * - NetworkPolicy
 */
public record TenantProvisionRequest(
    UUID tenantId,
    String namespace,
    ResourceQuotaSpec quota
) {
    public record ResourceQuotaSpec(
        int maxConnectors,
        int maxTopics
    ) {
        public static ResourceQuotaSpec defaultQuota() {
            return new ResourceQuotaSpec(20, 50);
        }
    }
}
