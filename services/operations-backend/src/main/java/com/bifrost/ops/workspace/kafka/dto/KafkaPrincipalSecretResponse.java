package com.bifrost.ops.workspace.kafka.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka principal Secret 조회 응답(#303).
 *
 * <p>플랫폼 정책상 Secret 원문(raw password)은 절대 반환하지 않는다. 이 응답은 Secret의 존재·위치·키 참조와
 * 마스킹된 password 표기만 제공한다(reference/masked only). 실제 자격증명은 K8s Secret에서 직접 마운트해 사용한다.
 */
public record KafkaPrincipalSecretResponse(
        UUID principalId,
        String username,
        String status,
        String namespace,
        String secretName,
        List<String> availableKeys,
        String passwordMasked,
        Instant retrievedAt,
        String exposurePolicy) {
}
