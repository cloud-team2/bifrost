package com.bifrost.ops.adapters.prometheus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prometheus HTTP API 클라이언트. instant(/api/v1/query) + range(/api/v1/query_range).
 * 접속 실패·타임아웃 시 예외를 그대로 던진다 — 호출부에서 fallback 처리.
 */
@Component
public class PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

    private final RestClient restClient;

    public PrometheusClient(
            @Value("${prometheus.url:http://localhost:9090}") String prometheusUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(prometheusUrl)
                .build();
    }

    /**
     * PromQL instant query를 실행하고 모든 result의 scalar 합을 반환한다.
     * (topic별 합산 등 sum() 없이 여러 series가 오는 경우에도 자동 합산)
     *
     * @throws RestClientException Prometheus 접속 실패 시
     */
    public double queryScalar(String promql) {
        // promql은 {topic="..."} 같은 중괄호를 포함한다. queryParam 값에 직접 넣으면
        // RestClient가 이를 URI 템플릿 변수로 오해해 확장에 실패한다(IllegalArgumentException).
        // {q} 템플릿의 값으로 바인딩하면 중괄호 재해석 없이 안전하게 인코딩된다.
        PrometheusResponse response = restClient.get()
                .uri(uri -> uri.path("/api/v1/query")
                        .queryParam("query", "{q}")
                        .build(promql))
                .retrieve()
                .body(PrometheusResponse.class);

        if (response == null || !"success".equals(response.status())
                || response.data() == null) {
            log.debug("Prometheus 빈 응답: query={}", promql);
            return 0.0;
        }

        List<PrometheusResponse.VectorResult> results = response.data().result();
        if (results == null || results.isEmpty()) return 0.0;

        return results.stream().mapToDouble(PrometheusResponse.VectorResult::scalar).sum();
    }

    /**
     * PromQL instant query를 실행하되 result series가 없으면 null을 반환한다.
     * 0 값과 "지표 소스 없음"을 구분해야 하는 임계 판단용 path에서 사용한다.
     */
    public Double queryScalarOrNull(String promql) {
        PrometheusResponse response = restClient.get()
                .uri(uri -> uri.path("/api/v1/query")
                        .queryParam("query", "{q}")
                        .build(promql))
                .retrieve()
                .body(PrometheusResponse.class);

        if (response == null || !"success".equals(response.status())
                || response.data() == null) {
            log.debug("Prometheus 빈 응답: query={}", promql);
            return null;
        }

        List<PrometheusResponse.VectorResult> results = response.data().result();
        if (results == null || results.isEmpty()) return null;

        return results.stream().mapToDouble(PrometheusResponse.VectorResult::scalar).sum();
    }

    /**
     * PromQL range query를 실행하고 timestamp(초)별 합산 값을 시간 오름차순 Map으로 반환한다.
     *
     * @param startSec 시작 epoch(초), endSec 종료 epoch(초), stepSec 간격(초)
     * @throws RestClientException Prometheus 접속 실패 시
     */
    public Map<Long, Double> queryRange(String promql, long startSec, long endSec, long stepSec) {
        PrometheusResponse response = restClient.get()
                .uri(uri -> uri.path("/api/v1/query_range")
                        .queryParam("query", "{q}")
                        .queryParam("start", "{s}")
                        .queryParam("end", "{e}")
                        .queryParam("step", "{st}")
                        .build(promql, startSec, endSec, stepSec))
                .retrieve()
                .body(PrometheusResponse.class);

        Map<Long, Double> byTs = new TreeMap<>();
        if (response == null || !"success".equals(response.status()) || response.data() == null) {
            log.debug("Prometheus range 빈 응답: query={}", promql);
            return byTs;
        }
        List<PrometheusResponse.VectorResult> results = response.data().result();
        if (results == null) return byTs;

        for (PrometheusResponse.VectorResult r : results) {
            for (List<Object> point : r.series()) {
                if (point.size() < 2) continue;
                long ts = (long) Double.parseDouble(String.valueOf(point.get(0)));
                double val = PrometheusResponse.VectorResult.parseVal(point);
                byTs.merge(ts, val, Double::sum);
            }
        }
        return byTs;
    }
}
