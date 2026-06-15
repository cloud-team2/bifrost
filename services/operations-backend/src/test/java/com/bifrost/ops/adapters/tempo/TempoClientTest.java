package com.bifrost.ops.adapters.tempo;

import com.bifrost.ops.internalops.dto.TraceSummaryResult.TraceSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #373: Tempo {@code /api/traces/{id}} OTLP JSON 파싱 검증. HTTP는 TraceQuery 테스트(mock)에서 다룬다.
 */
class TempoClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void parsesSpansWithServiceDurationAndError() throws Exception {
        // batches → resource(service.name) → scopeSpans → spans. 1개는 error, 1개는 ok.
        JsonNode trace = json("""
            {
              "batches": [{
                "resource": { "attributes": [
                  { "key": "service.name", "value": { "stringValue": "platform-connect" } }
                ]},
                "scopeSpans": [{
                  "spans": [
                    { "name": "source-poll", "startTimeUnixNano": "1000000000", "endTimeUnixNano": "1005000000",
                      "status": { "code": "STATUS_CODE_OK" } },
                    { "name": "sink-put", "startTimeUnixNano": "1005000000", "endTimeUnixNano": "1009000000",
                      "status": { "code": "STATUS_CODE_ERROR", "message": "type mismatch" } }
                  ]
                }]
              }]
            }
            """);

        List<TraceSpan> spans = TempoClient.parseTrace(trace);

        assertThat(spans).hasSize(2);
        TraceSpan source = spans.get(0);
        assertThat(source.name()).isEqualTo("source-poll");
        assertThat(source.service()).isEqualTo("platform-connect");
        assertThat(source.durationMs()).isEqualTo(5L);   // (1005-1000)ms
        assertThat(source.status()).isEqualTo("ok");
        assertThat(source.error()).isNull();

        TraceSpan sink = spans.get(1);
        assertThat(sink.name()).isEqualTo("sink-put");
        assertThat(sink.durationMs()).isEqualTo(4L);
        assertThat(sink.status()).isEqualTo("error");
        assertThat(sink.error()).isEqualTo("type mismatch");
    }

    @Test
    void supportsLegacyInstrumentationLibrarySpansAndNumericStatusCode() throws Exception {
        JsonNode trace = json("""
            {
              "batches": [{
                "resource": { "attributes": [
                  { "key": "service.name", "value": { "stringValue": "operations-backend" } }
                ]},
                "instrumentationLibrarySpans": [{
                  "spans": [
                    { "name": "pipeline.create", "startTimeUnixNano": "0", "endTimeUnixNano": "2000000",
                      "status": { "code": 2 } }
                  ]
                }]
              }]
            }
            """);

        List<TraceSpan> spans = TempoClient.parseTrace(trace);

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).service()).isEqualTo("operations-backend");
        assertThat(spans.get(0).durationMs()).isEqualTo(2L);
        assertThat(spans.get(0).status()).isEqualTo("error");
    }

    @Test
    void emptyOrMalformedTraceYieldsNoSpans() throws Exception {
        assertThat(TempoClient.parseTrace(null)).isEmpty();
        assertThat(TempoClient.parseTrace(json("{}"))).isEmpty();
        assertThat(TempoClient.parseTrace(json("{\"batches\":[]}"))).isEmpty();
    }

    // (#708) /api/search 결과에서 가장 최근(startTimeUnixNano 최대) trace 선택 — limit=1 고정 반환 버그 수정.
    @Test
    void newestTracePicksLatestByStartTimeRegardlessOfOrder() throws Exception {
        JsonNode search = json("""
            { "traces": [
              { "traceID": "old",    "startTimeUnixNano": "1000000000000", "durationMs": 5 },
              { "traceID": "newest", "startTimeUnixNano": "3000000000000", "durationMs": 7 },
              { "traceID": "mid",    "startTimeUnixNano": "2000000000000", "durationMs": 6 }
            ]}
            """);

        JsonNode newest = TempoClient.newestTrace(search);

        assertThat(newest).isNotNull();
        assertThat(newest.path("traceID").asText()).isEqualTo("newest");
    }

    @Test
    void newestTraceNullWhenNoTraces() throws Exception {
        assertThat(TempoClient.newestTrace(null)).isNull();
        assertThat(TempoClient.newestTrace(json("{}"))).isNull();
        assertThat(TempoClient.newestTrace(json("{\"traces\":[]}"))).isNull();
    }
}
