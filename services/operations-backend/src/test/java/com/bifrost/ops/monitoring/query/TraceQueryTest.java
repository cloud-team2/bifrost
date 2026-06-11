package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.tempo.TempoClient;
import com.bifrost.ops.adapters.tempo.TempoClient.TempoTrace;
import com.bifrost.ops.internalops.dto.TraceSummaryResult;
import com.bifrost.ops.internalops.dto.TraceSummaryResult.TraceSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #373: query_traces Tempo 조회 — 비활성/미발견/실패는 stub, 활성은 trace 요약(#391 metrics와 동일 정책).
 */
class TraceQueryTest {

    @Test
    void disabledReturnsStubWithDerivedPipelineId() {
        TraceQuery q = new TraceQuery(false, mock(TempoClient.class));

        TraceSummaryResult r = q.query("11111111-2222-3333-4444-555555555555-source", "cdc.orders");

        assertThat(q.isEnabled()).isFalse();
        assertThat(r.traceId()).isNull();
        assertThat(r.pipelineId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(r.status()).isEqualTo("unknown");
        assertThat(r.spans()).isEmpty();
        assertThat(r.note()).contains("비활성화");
    }

    @Test
    void enabledNarrowsByTopicAndReturnsTraceSummary() {
        TempoClient client = mock(TempoClient.class);
        TraceSpan sink = new TraceSpan("sink-put", "platform-connect", 4L, "error", "type mismatch");
        when(client.recentTrace(contains("messaging.destination.name=\"cdc.orders\""), anyLong(), anyLong()))
                .thenReturn(Optional.of(new TempoTrace("abc123", 9L, true, List.of(sink))));
        TraceQuery q = new TraceQuery(true, client);

        TraceSummaryResult r = q.query("p1-sink", "cdc.orders");

        assertThat(r.traceId()).isEqualTo("abc123");
        assertThat(r.pipelineId()).isEqualTo("p1");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.durationMs()).isEqualTo(9L);
        assertThat(r.spans()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("sink-put");
            assertThat(s.status()).isEqualTo("error");
        });
    }

    @Test
    void enabledButNoTraceReturnsStub() {
        TempoClient client = mock(TempoClient.class);
        when(client.recentTrace(eq("{ resource.service.name=\"platform-connect\" }"), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        TraceQuery q = new TraceQuery(true, client);

        TraceSummaryResult r = q.query("p1-source", null);   // topic 없음 → service 범위 폴백

        assertThat(r.traceId()).isNull();
        assertThat(r.note()).contains("trace 없음");
    }

    @Test
    void tempoFailureFallsBackToStub() {
        TempoClient client = mock(TempoClient.class);
        when(client.recentTrace(contains("platform-connect"), anyLong(), anyLong()))
                .thenThrow(new org.springframework.web.client.RestClientException("connection refused"));
        TraceQuery q = new TraceQuery(true, client);

        TraceSummaryResult r = q.query("p1-source", "cdc.orders");

        assertThat(r.traceId()).isNull();
        assertThat(r.note()).contains("실패");
    }

    @Test
    void queryByIdReturnsTraceSummary() {
        TempoClient client = mock(TempoClient.class);
        TraceSpan span = new TraceSpan("sink-put", "platform-connect", 4L, "error", "type mismatch");
        when(client.traceById("abc123"))
                .thenReturn(Optional.of(new TempoTrace("abc123", 9L, true, List.of(span))));
        TraceQuery q = new TraceQuery(true, client);

        TraceSummaryResult r = q.queryById("p1-source", "abc123");

        assertThat(r.traceId()).isEqualTo("abc123");
        assertThat(r.pipelineId()).isEqualTo("p1");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.durationMs()).isEqualTo(9L);
        assertThat(r.spans()).singleElement().extracting(TraceSpan::name).isEqualTo("sink-put");
    }

    @Test
    void queryByIdDisabledReturnsStub() {
        TraceQuery q = new TraceQuery(false, mock(TempoClient.class));
        TraceSummaryResult r = q.queryById("p1-source", "abc123");
        assertThat(r.traceId()).isNull();
        assertThat(r.note()).contains("비활성화");
    }

    @Test
    void queryByIdMissingReturnsStub() {
        TempoClient client = mock(TempoClient.class);
        when(client.traceById("missing")).thenReturn(Optional.empty());
        TraceQuery q = new TraceQuery(true, client);
        TraceSummaryResult r = q.queryById("p1-source", "missing");
        assertThat(r.traceId()).isNull();
        assertThat(r.note()).contains("trace 없음");
    }
}
