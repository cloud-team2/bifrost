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
import java.util.Locale;
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
 * <p>logical metric → PromQL 매핑은 live kube-prometheus-stack에 실제 존재하는 metric만 둔다.
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
        String namespace = labelValue(kafkaNamespace);
        String brokerPodRegex = regexLabelValue(kafkaNamespace) + "-kafka-[0-9]+";
        String topicRegex = projectTopicRegex(projectKey);
        String debeziumServerRegex = "cdc\\\\.table\\\\." + regexLabelValue(projectKey) + "\\\\..*";
        return switch (metric) {
            case "pipeline_lag_seconds" ->
                    "sum(kafka_consumergroup_lag{namespace=\"" + namespace + "\",topic=~\"" + topicRegex + "\"})";
            case "consumer_lag_p95" ->
                    "quantile(0.95, kafka_consumergroup_lag{namespace=\"" + namespace
                            + "\",topic=~\"" + topicRegex + "\"})";
            case "consumer_commit_rate_per_sec" ->
                    "sum(rate(kafka_consumergroup_current_offset{namespace=\"" + namespace
                            + "\",topic=~\"" + topicRegex + "\"}[5m]))";
            case "topic_ingress_messages_per_sec" ->
                    "sum(rate(kafka_topic_partition_current_offset{namespace=\"" + namespace
                            + "\",topic=~\"" + topicRegex + "\"}[5m]))";
            case "source_freshness_delay_ms", "source_watermark_delay_ms" ->
                    "max(debezium_metrics_millisecondsbehindsource{namespace=\"" + namespace
                            + "\",server=~\"" + debeziumServerRegex + "\"})";
            case "source_event_rate_per_sec" ->
                    "sum(rate(debezium_metrics_totalnumberofeventsseen{namespace=\"" + namespace
                            + "\",server=~\"" + debeziumServerRegex + "\"}[5m]))";
            case "broker_cpu_cores" ->
                    "sum(rate(container_cpu_usage_seconds_total{namespace=\"" + namespace
                            + "\",pod=~\"" + brokerPodRegex + "\"}[5m]))";
            case "broker_memory_working_set_bytes" ->
                    "sum(container_memory_working_set_bytes{namespace=\"" + namespace
                            + "\",pod=~\"" + brokerPodRegex + "\"})";
            case "broker_network_receive_bytes_per_sec" ->
                    "sum(rate(container_network_receive_bytes_total{namespace=\"" + namespace
                            + "\",pod=~\"" + brokerPodRegex + "\"}[5m]))";
            case "broker_network_transmit_bytes_per_sec" ->
                    "sum(rate(container_network_transmit_bytes_total{namespace=\"" + namespace
                            + "\",pod=~\"" + brokerPodRegex + "\"}[5m]))";
            case "broker_fs_read_bytes_per_sec" ->
                    "sum(rate(container_fs_reads_bytes_total{namespace=\"" + namespace
                            + "\",pod=~\"" + brokerPodRegex + "\"}[5m]))";
            case "broker_fs_write_bytes_per_sec" ->
                    "sum(rate(container_fs_writes_bytes_total{namespace=\"" + namespace
                            + "\",pod=~\"" + brokerPodRegex + "\"}[5m]))";
            default -> null;
        };
    }

    private static String projectTopicRegex(String projectKey) {
        return "(cdc|eda)\\\\.table\\\\." + regexLabelValue(projectKey) + "\\\\..*";
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
        double first = points.getFirst().value();
        double latest = points.get(points.size() - 1).value();
        String trend = trend(first, latest);
        return switch (metric) {
            case "pipeline_lag_seconds" -> String.format(
                    Locale.ROOT,
                    "consumer lag metric pipeline_lag_seconds: %d points, latest=%.3f%s",
                    points.size(), latest, trend);
            case "consumer_lag_p95" -> String.format(
                    Locale.ROOT,
                    "consumer lag p95 metric: %d points, latest=%.3f%s%s",
                    points.size(), latest, trend, trendEvidence(first, latest, "; lag p95 증가", "; lag p95 감소"));
            case "consumer_commit_rate_per_sec" -> String.format(
                    Locale.ROOT,
                    "offset progression commit rate metric: %d points, latest=%.3f records/sec%s%s",
                    points.size(), latest, trend, trendEvidence(first, latest, "; commit rate 증가", "; offset progression 둔화 commit rate 감소"));
            case "topic_ingress_messages_per_sec" -> String.format(
                    Locale.ROOT,
                    "topic ingress rate metric: %d points, latest=%.3f messages/sec%s%s",
                    points.size(), latest, trend, trendEvidence(first, latest, "; topic ingress 증가", "; topic ingress 감소"));
            case "source_freshness_delay_ms", "source_watermark_delay_ms" -> String.format(
                    Locale.ROOT,
                    "source watermark freshness delay metric: %d points, latest=%.3f ms%s",
                    points.size(), latest, trend);
            case "source_event_rate_per_sec" -> String.format(
                    Locale.ROOT,
                    "source event volume metric: %d points, latest=%.3f events/sec%s",
                    points.size(), latest, trend);
            case "broker_cpu_cores" -> String.format(
                    Locale.ROOT,
                    "broker resource saturation CPU metric: %d points, latest=%.3f cores%s",
                    points.size(), latest, trend);
            case "broker_memory_working_set_bytes" -> String.format(
                    Locale.ROOT,
                    "broker resource saturation memory metric: %d points, latest=%.3f bytes%s",
                    points.size(), latest, trend);
            case "broker_network_receive_bytes_per_sec" -> String.format(
                    Locale.ROOT,
                    "broker resource saturation network receive metric: %d points, latest=%.3f bytes/sec%s",
                    points.size(), latest, trend);
            case "broker_network_transmit_bytes_per_sec" -> String.format(
                    Locale.ROOT,
                    "broker resource saturation network transmit metric: %d points, latest=%.3f bytes/sec%s",
                    points.size(), latest, trend);
            case "broker_fs_read_bytes_per_sec" -> String.format(
                    Locale.ROOT,
                    "broker resource saturation disk read metric: %d points, latest=%.3f bytes/sec%s",
                    points.size(), latest, trend);
            case "broker_fs_write_bytes_per_sec" -> String.format(
                    Locale.ROOT,
                    "broker resource saturation disk write metric: %d points, latest=%.3f bytes/sec%s",
                    points.size(), latest, trend);
            default -> String.format(Locale.ROOT, "%s: %d points, latest=%.3f%s", metric, points.size(), latest, trend);
        };
    }

    private static String trend(double first, double latest) {
        if (Double.compare(first, latest) == 0) {
            return "";
        }
        double delta = latest - first;
        return String.format(Locale.ROOT, ", first=%.3f, delta=%.3f", first, delta);
    }

    private static String trendEvidence(double first, double latest, String positive, String negative) {
        int comparison = Double.compare(latest, first);
        if (comparison > 0) {
            return positive;
        }
        if (comparison < 0) {
            return negative;
        }
        return "";
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
