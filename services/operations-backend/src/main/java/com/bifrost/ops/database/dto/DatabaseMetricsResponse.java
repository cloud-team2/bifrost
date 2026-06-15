package com.bifrost.ops.database.dto;

/**
 * DB 지표 응답(#30, FR-017). 등록 DB에 직접 연결해 엔진 통계 뷰와 probe query로 산출한다.
 *
 * @param tps              초당 트랜잭션 수
 * @param queryResponseMs  평균 쿼리 응답시간(ms)
 * @param queryResponseP95Ms p95 쿼리 응답시간(ms). 엔진 통계 소스가 없으면 null
 * @param activeConnections 활성 연결 수
 * @param stub             아직 실데이터 아님(placeholder)
 */
public record DatabaseMetricsResponse(
        double tps,
        double queryResponseMs,
        Double queryResponseP95Ms,
        int activeConnections,
        boolean stub
) {

    public static DatabaseMetricsResponse live(double tps, double queryResponseMs,
                                               Double queryResponseP95Ms, int activeConnections) {
        return new DatabaseMetricsResponse(tps, queryResponseMs, queryResponseP95Ms, activeConnections, false);
    }

    /** 실수집 불가 환경의 계약용 placeholder. 실제 metric path에서는 사용하지 않는다. */
    public static DatabaseMetricsResponse placeholder() {
        return new DatabaseMetricsResponse(0.0, 0.0, null, 0, true);
    }
}
