package com.bifrost.ops.pipeline.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 파이프라인 consumer 연결 가이드(#303).
 *
 * <p>자격증명 원문은 포함하지 않고 Kubernetes Secret 이름/키 참조만 전달한다.
 */
public record ConnectionGuideResponse(
        UUID pipelineId,
        String pipelineName,
        String bootstrapServers,
        String recommendedGroupId,
        String authenticationMethod,
        SecretReference credentialReference,
        List<AuthTemplate> authenticationTemplates,
        List<TopicRef> topics) {

    public record SecretReference(
            String namespace,
            String secretName,
            Map<String, String> keyRefs,
            List<String> availableKeys) {
    }

    public record AuthTemplate(
            String type,
            String securityProtocol,
            Map<String, String> properties,
            SecretReference credentialReference) {
    }

    public record TopicRef(
            String name,
            String sourceTable,
            String role) {
    }
}
