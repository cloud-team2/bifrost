package com.bifrost.ops.governance;

import java.util.Map;
import java.util.UUID;

/**
 * 거버넌스 게이트에 전달되는 mutation 요청 컨텍스트(S3).
 *
 * @param tenantId       워크스페이스 ID
 * @param actor          요청자(email 또는 "system")
 * @param operation      실행할 operation 이름 (PolicyGuard의 RISK_MAP 키)
 * @param targetType     대상 타입 (예: "CONNECTOR")
 * @param targetId       대상 ID
 * @param idempotencyKey 멱등성 키 (null 허용 시 멱등성 미검사)
 * @param approvalId     승인 토큰 ID (PolicyDecision=REQUIRE_APPROVAL일 때 필수)
 * @param changeTicketId 변경 티켓 ID (PolicyDecision=REQUIRE_CHANGE_MANAGEMENT일 때 필수)
 * @param paramsHash     params SHA-256 해시 (approval 검증용)
 * @param beforeSnapshot mutation 전 상태 스냅샷 (evidence 저장용)
 */
public record MutationContext(
        UUID tenantId,
        String actor,
        String operation,
        String targetType,
        UUID targetId,
        String idempotencyKey,
        UUID approvalId,
        UUID changeTicketId,
        String paramsHash,
        Map<String, Object> beforeSnapshot
) {}
