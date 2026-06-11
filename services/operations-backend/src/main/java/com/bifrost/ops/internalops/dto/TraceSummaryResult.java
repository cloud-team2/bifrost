package com.bifrost.ops.internalops.dto;

import java.util.List;

/**
 * query_traces 결과 — Tempo 분산 trace summary(#373).
 *
 * <p>변경 이벤트가 source → topic → sink로 흐르며 어디서 지연/실패했는지를 한 trace로 요약한다.
 * ai-service {@code get_traces}({@code TracesData})와 정합한다(snake_case 필드는 ai-service 측
 * {@code alias_generator(to_camel)}가 camelCase wire로 수용).
 *
 * <p>{@code tempo.enabled=false}(기본)·trace 미발견·Tempo 조회 실패 시 {@link #stub}로
 * well-formed 빈 결과를 반환한다(항상 200 + 파싱 안전, #391 metrics와 동일 정책).
 */
public record TraceSummaryResult(
        String traceId,
        String pipelineId,
        String status,
        long durationMs,
        List<TraceSpan> spans,
        String note) {

    /** 개별 span 요약: 무엇이(name) 어느 서비스에서(service) 얼마나(durationMs) 걸렸고 실패(status/error)했나. */
    public record TraceSpan(String name, String service, long durationMs, String status, String error) {}

    public static TraceSummaryResult of(String traceId, String pipelineId, String status,
                                        long durationMs, List<TraceSpan> spans) {
        return new TraceSummaryResult(traceId, pipelineId, status, durationMs,
                spans == null ? List.of() : spans, null);
    }

    /** Tempo 비활성·trace 미발견·조회 실패 시의 well-formed 빈 결과(파싱 안전). */
    public static TraceSummaryResult stub(String pipelineId, String note) {
        return new TraceSummaryResult(null, pipelineId, "unknown", 0L, List.of(), note);
    }
}
