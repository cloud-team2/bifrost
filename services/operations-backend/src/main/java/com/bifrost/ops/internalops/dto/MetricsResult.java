package com.bifrost.ops.internalops.dto;

import java.util.List;

/**
 * query_metrics 결과(#391).
 *
 * <p>ai-service {@code MetricsData}({@code {metric, summary, dataPoints:[{timestamp, value}]}})와
 * 정합한다(snake_case 필드는 ai-service 측 alias_generator(to_camel)가 dataPoints로 수용).
 */
public record MetricsResult(String metric, String summary, List<MetricsDataPoint> dataPoints) {

    public record MetricsDataPoint(String timestamp, double value) {}

    public static MetricsResult of(String metric, String summary, List<MetricsDataPoint> dataPoints) {
        return new MetricsResult(metric, summary, dataPoints == null ? List.of() : dataPoints);
    }

    /** Prometheus 비활성·미지원 metric·조회 실패 시의 well-formed 빈 결과(파싱 안전). */
    public static MetricsResult stub(String metric, String summary) {
        return new MetricsResult(metric, summary, List.of());
    }
}
