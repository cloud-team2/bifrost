package com.bifrost.ops.database.dto;

import com.bifrost.ops.database.connection.DbConnectionFailureReason;

/**
 * 연결 테스트 결과. 실패도 HTTP 200으로 반환하고 {@code reason}(5종)으로 분류한다.
 *
 * @param success   연결·{@code SELECT 1} 성공 여부
 * @param reason    실패 분류(성공 시 {@code null})
 * @param message   사람용 안내 문구
 * @param latencyMs 시도에 걸린 시간(ms)
 */
public record ConnectionTestResponse(
        boolean success,
        DbConnectionFailureReason reason,
        String message,
        long latencyMs
) {

    public static ConnectionTestResponse ok(long latencyMs) {
        return new ConnectionTestResponse(true, null, "연결 성공", latencyMs);
    }

    public static ConnectionTestResponse fail(DbConnectionFailureReason reason, long latencyMs) {
        return new ConnectionTestResponse(false, reason, reason.defaultMessage(), latencyMs);
    }
}
