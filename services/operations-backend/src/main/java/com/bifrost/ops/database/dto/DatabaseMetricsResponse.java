package com.bifrost.ops.database.dto;

/**
 * DB 지표 응답(#30, FR-017). 이번 주는 계약(응답 shape) 확정용 <b>stub</b>이다 — 실수집은
 * monitoring.collector(DB ping·통계 뷰) 연동 시 채운다. {@code stub=true}로 프론트가 placeholder
 * 처리할 수 있게 한다.
 *
 * @param tps              초당 트랜잭션 수
 * @param queryResponseMs  평균 쿼리 응답시간(ms)
 * @param activeConnections 활성 연결 수
 * @param stub             아직 실데이터 아님(placeholder)
 */
public record DatabaseMetricsResponse(
        double tps,
        double queryResponseMs,
        int activeConnections,
        boolean stub
) {

    /** 실수집 전 계약용 placeholder 응답. */
    public static DatabaseMetricsResponse placeholder() {
        return new DatabaseMetricsResponse(0.0, 0.0, 0, true);
    }
}
