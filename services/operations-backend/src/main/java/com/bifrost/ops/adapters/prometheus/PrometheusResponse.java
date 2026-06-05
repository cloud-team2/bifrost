package com.bifrost.ops.adapters.prometheus;

import java.util.List;
import java.util.Map;

/**
 * Prometheus HTTP API 응답 봉투.
 * <ul>
 *   <li>instant query(/api/v1/query, vector): {@code value} = [ts, val]</li>
 *   <li>range query(/api/v1/query_range, matrix): {@code values} = [[ts, val], ...]</li>
 * </ul>
 * ts/val이 혼합(Double/String)이라 List&lt;Object&gt;로 받는다.
 */
public record PrometheusResponse(String status, Data data) {

    public record Data(String resultType, List<VectorResult> result) {}

    public record VectorResult(Map<String, String> metric, List<Object> value, List<List<Object>> values) {

        /** instant scalar 값 추출. 조회 실패·파싱 오류 시 0.0 반환. */
        public double scalar() {
            return parseVal(value);
        }

        /** matrix 시계열 [ts(초), value] 목록. range query 결과용. */
        public List<List<Object>> series() {
            return values == null ? List.of() : values;
        }

        /** [ts, val] 쌍의 두 번째 요소를 double로 파싱. */
        public static double parseVal(List<Object> pair) {
            if (pair == null || pair.size() < 2) return 0.0;
            try {
                return Double.parseDouble(String.valueOf(pair.get(1)));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
    }
}
