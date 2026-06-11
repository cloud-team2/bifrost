package com.bifrost.ops.adapters.tempo;

import com.bifrost.ops.internalops.dto.TraceSummaryResult.TraceSpan;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Grafana Tempo HTTP query API 클라이언트(#373). 검색(/api/search) + trace 상세(/api/traces/{id}).
 *
 * <p>접속 실패·타임아웃 시 예외를 그대로 던진다 — 호출부({@code TraceQuery})에서 stub fallback 처리.
 * OTLP trace JSON 파싱은 {@link #parseTrace}(static, 단위 테스트 대상)로 분리한다.
 */
@Component
public class TempoClient {

    private static final Logger log = LoggerFactory.getLogger(TempoClient.class);

    private final RestClient restClient;

    public TempoClient(@Value("${tempo.url:http://tempo.monitoring:3200}") String tempoUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        this.restClient = RestClient.builder()
                .baseUrl(tempoUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * TraceQL로 구간 내 가장 최근 trace 1건을 검색·조회해 요약을 반환한다. 없으면 empty.
     *
     * @throws RestClientException Tempo 접속 실패 시
     */
    public Optional<TempoTrace> recentTrace(String traceql, long startSec, long endSec) {
        JsonNode search = restClient.get()
                .uri(uri -> uri.path("/api/search")
                        // TraceQL은 {}를 포함 → URI 템플릿 변수 오해 방지를 위해 {q} 바인딩(PrometheusClient와 동일 기법)
                        .queryParam("q", "{q}")
                        .queryParam("start", startSec)
                        .queryParam("end", endSec)
                        .queryParam("limit", 1)
                        .build(traceql))
                .retrieve()
                .body(JsonNode.class);

        JsonNode traces = search == null ? null : search.get("traces");
        if (traces == null || !traces.isArray() || traces.isEmpty()) {
            return Optional.empty();
        }
        JsonNode head = traces.get(0);
        String traceId = head.path("traceID").asText(null);
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        // Tempo /api/search 응답의 durationMs는 millisecond 단위 → 아래 span 계산값과 같은 단위라 Math.max 안전.
        long durationMs = head.path("durationMs").asLong(0L);

        return traceById(traceId)
                .map(t -> new TempoTrace(t.traceId(), Math.max(durationMs, t.durationMs()), t.error(), t.spans()));
    }

    /**
     * traceId로 trace 상세를 직접 조회·요약한다. 없거나 spans 비면 empty.
     *
     * @throws RestClientException 접속 실패
     */
    public Optional<TempoTrace> traceById(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        JsonNode detail = restClient.get()
                .uri("/api/traces/{id}", traceId)
                .retrieve()
                .body(JsonNode.class);
        List<TraceSpan> spans = parseTrace(detail);
        if (spans.isEmpty()) {
            return Optional.empty();
        }
        long durationMs = spans.stream().mapToLong(TraceSpan::durationMs).max().orElse(0L);
        boolean error = spans.stream().anyMatch(s -> "error".equals(s.status()));
        return Optional.of(new TempoTrace(traceId, durationMs, error, spans));
    }

    /** Tempo {@code /api/traces/{id}} OTLP JSON(batches → resource/scopeSpans → spans)을 span 요약 목록으로 파싱. */
    static List<TraceSpan> parseTrace(JsonNode trace) {
        List<TraceSpan> out = new ArrayList<>();
        if (trace == null) {
            return out;
        }
        // Tempo는 batches, OTLP exporter는 resourceSpans — 둘 다 수용.
        JsonNode batches = trace.has("batches") ? trace.get("batches") : trace.get("resourceSpans");
        if (batches == null || !batches.isArray()) {
            return out;
        }
        for (JsonNode batch : batches) {
            String service = attr(batch.path("resource").path("attributes"), "service.name");
            // scopeSpans(신) / instrumentationLibrarySpans(구) 모두 수용
            JsonNode scopes = batch.has("scopeSpans") ? batch.get("scopeSpans") : batch.get("instrumentationLibrarySpans");
            if (scopes == null || !scopes.isArray()) {
                continue;
            }
            for (JsonNode scope : scopes) {
                JsonNode spans = scope.get("spans");
                if (spans == null || !spans.isArray()) {
                    continue;
                }
                for (JsonNode s : spans) {
                    long startNs = s.path("startTimeUnixNano").asLong(0L);
                    long endNs = s.path("endTimeUnixNano").asLong(0L);
                    long durMs = endNs > startNs ? (endNs - startNs) / 1_000_000L : 0L;
                    JsonNode status = s.path("status");
                    boolean isError = isErrorStatus(status);
                    String error = isError ? status.path("message").asText("") : null;
                    out.add(new TraceSpan(
                            s.path("name").asText("unknown"),
                            service == null ? "unknown" : service,
                            durMs,
                            isError ? "error" : "ok",
                            (error == null || error.isBlank()) ? null : error));
                }
            }
        }
        return out;
    }

    /** OTLP status code는 문자열("STATUS_CODE_ERROR")·정수(2) 둘 다 가능. */
    private static boolean isErrorStatus(JsonNode status) {
        if (status == null || status.isMissingNode()) {
            return false;
        }
        JsonNode code = status.get("code");
        if (code == null) {
            return false;
        }
        if (code.isNumber()) {
            return code.asInt() == 2;
        }
        return "STATUS_CODE_ERROR".equals(code.asText());
    }

    /** OTLP attributes 배열에서 key의 stringValue를 찾는다. */
    private static String attr(JsonNode attributes, String key) {
        if (attributes == null || !attributes.isArray()) {
            return null;
        }
        for (JsonNode a : attributes) {
            if (key.equals(a.path("key").asText(null))) {
                JsonNode v = a.path("value");
                String s = v.path("stringValue").asText(null);
                return s != null ? s : v.asText(null);
            }
        }
        return null;
    }

    /** 파싱된 trace 요약(어댑터 내부 모델). */
    public record TempoTrace(String traceId, long durationMs, boolean error, List<TraceSpan> spans) {}
}
