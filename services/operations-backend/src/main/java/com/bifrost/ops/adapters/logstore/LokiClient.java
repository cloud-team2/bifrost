package com.bifrost.ops.adapters.logstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loki HTTP API 클라이언트(S4).
 * GET /loki/api/v1/query_range 로 로그를 조회한다.
 */
@Component
public class LokiClient {

    private static final Logger log = LoggerFactory.getLogger(LokiClient.class);

    private final RestClient restClient;

    public LokiClient(@Value("${loki.url:http://loki.monitoring:3100}") String lokiUrl) {
        this.restClient = RestClient.builder().baseUrl(lokiUrl).build();
    }

    /**
     * LogQL query_range 실행 후 로그 라인 목록을 반환한다.
     * @param query  LogQL 쿼리 (예: {@code {job="kafka-connect"} |= "error"})
     * @param startNs 시작 epoch nanoseconds
     * @param endNs   종료 epoch nanoseconds
     * @param limit   최대 결과 수
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryRange(String query, long startNs, long endNs, int limit) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uri -> uri.path("/loki/api/v1/query_range")
                            .queryParam("query", "{q}")
                            .queryParam("start", "{s}")
                            .queryParam("end", "{e}")
                            .queryParam("limit", "{l}")
                            .build(query, startNs, endNs, limit))
                    .retrieve()
                    .body(Map.class);

            return parseLogs(response);
        } catch (RestClientException e) {
            log.debug("Loki 조회 실패(무시): {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseLogs(Map<String, Object> response) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (response == null) return entries;

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return entries;

        List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
        if (result == null) return entries;

        for (Map<String, Object> stream : result) {
            Map<String, Object> labels = (Map<String, Object>) stream.get("stream");
            List<List<Object>> values = (List<List<Object>>) stream.get("values");
            if (values == null) continue;
            for (List<Object> point : values) {
                if (point.size() < 2) continue;
                entries.add(Map.of(
                        "ts", point.get(0),
                        "line", point.get(1),
                        "labels", labels != null ? labels : Map.of()));
            }
        }
        return entries;
    }
}
