package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.internalops.dto.MetricsResult;
import com.bifrost.ops.internalops.dto.MetricsResult.MetricsDataPoint;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * query_metrics PromQL 조회 서비스(#391, ai-service {@code get_metrics} tool 대응).
 *
 * <p>{@link com.bifrost.ops.monitoring.query.KafkaMetricsQuery}와 동일 정책:
 * {@code prometheus.enabled=false}(기본)면 Prometheus를 호출하지 않고 well-formed stub을 반환하고,
 * true면 range query를 실행하되 미지원 metric·접속 실패 시 stub으로 폴백한다(항상 200 + 파싱 안전).
 *
 * <p>logical metric → PromQL 매핑은 현재 {@code pipeline_lag_seconds} 1종이며, 나머지 metric
 * (CPU·memory·network 등)은 Future Work로 확장한다(#391 참조).
 */
@Service
public class ObservabilityMetricsQuery {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityMetricsQuery.class);
    /** "last_30m" / "30m" / "1h" 형태 허용. 미일치 시 기본 윈도우. */
    private static final Pattern WINDOW = Pattern.compile("(?:last[_-])?(\\d+)\\s*([mh])");
    private static final long DEFAULT_WINDOW_SEC = 1800L; // 30m
    private static final long MIN_STEP_SEC = 15L;
    private static final long TARGET_POINTS = 30L;

    private final boolean enabled;
    private final PrometheusClient client;
    private final String kafkaNamespace;

    public ObservabilityMetricsQuery(
            @Value("${prometheus.enabled:false}") boolean enabled,
            PrometheusClient client,
            @Value("${kafka-cluster.namespace:platform-kafka}") String kafkaNamespace) {
        this.enabled = enabled;
        this.client = client;
        this.kafkaNamespace = nonBlankOrDefault(kafkaNamespace, "platform-kafka");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** metric(logical name) + timeRange(예: "last_30m")로 시계열을 조회한다. 항상 non-null 결과. */
    public MetricsResult query(WorkspaceEntity workspace, String metric, String timeRange) {
        if (!enabled) {
            return MetricsResult.stub(metric, "Prometheus 비활성화 — stub 응답 (metric=" + metric + ")");
        }
        String promql = promqlFor(metric, projectKey(workspace), kafkaNamespace);
        if (promql == null) {
            return MetricsResult.stub(metric, "지원하지 않는 metric: " + metric);
        }
        long windowSec = parseWindowSeconds(timeRange);
        long end = Instant.now().getEpochSecond();
        long start = end - windowSec;
        long step = Math.max(MIN_STEP_SEC, windowSec / TARGET_POINTS);
        try {
            Map<Long, Double> series = client.queryRange(promql, start, end, step);
            List<MetricsDataPoint> points = series.entrySet().stream()
                    .map(e -> new MetricsDataPoint(Instant.ofEpochSecond(e.getKey()).toString(), e.getValue()))
                    .toList();
            return MetricsResult.of(metric, summarize(metric, points), points);
        } catch (RestClientException e) {
            log.debug("Prometheus query_metrics 실패(stub fallback): metric={} cause={}", metric, e.getMessage());
            return MetricsResult.stub(metric, "Prometheus 조회 실패 — stub 응답 (metric=" + metric + ")");
        }
    }

    /** 알려진 logical metric → PromQL. 미지원이면 null(호출부에서 stub). */
    private static String promqlFor(String metric, String projectKey, String kafkaNamespace) {
        if (metric == null) {
            return null;
        }
        return switch (metric) {
            case "pipeline_lag_seconds" ->
                    "sum(kafka_consumergroup_lag{namespace=\"" + labelValue(kafkaNamespace)
                            + "\",topic=~\"(cdc|eda)\\\\.table\\\\."
                            + regexLabelValue(projectKey) + "\\\\..*\"})";
            default -> null;
        };
    }

    private static String projectKey(WorkspaceEntity workspace) {
        return workspace.getNamespace() != null && !workspace.getNamespace().isBlank()
                ? workspace.getNamespace()
                : workspace.getId().toString();
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String regexLabelValue(String raw) {
        return labelValue(escapeRegex(raw));
    }

    private static String escapeRegex(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ("\\.[]{}()+*?^$|".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String labelValue(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String summarize(String metric, List<MetricsDataPoint> points) {
        if (points.isEmpty()) {
            return metric + ": 구간 내 데이터 없음";
        }
        double latest = points.get(points.size() - 1).value();
        return String.format("%s: %d points, latest=%.3f", metric, points.size(), latest);
    }

    private static long parseWindowSeconds(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return DEFAULT_WINDOW_SEC;
        }
        Matcher m = WINDOW.matcher(timeRange.trim().toLowerCase());
        if (!m.matches()) {
            return DEFAULT_WINDOW_SEC;
        }
        long n = Long.parseLong(m.group(1));
        long unitSec = "h".equals(m.group(2)) ? 3600L : 60L;
        long sec = n * unitSec;
        return sec > 0 ? sec : DEFAULT_WINDOW_SEC;
    }
}
