package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Kafka 토픽 메트릭 PromQL 조회 서비스.
 *
 * <p>prometheus.enabled=false(기본)면 모든 메서드가 즉시 0을 반환한다.
 * true면 Prometheus HTTP API를 호출하고, 실패 시 예외를 던져 호출부 fallback을 트리거한다.
 *
 * <p>세 메트릭 모두 Kafka Exporter의 offset 계열을 쓴다(JMX MessagesOutPerSec는
 * Kafka가 토픽별로 내보내지 않아 항상 0이므로 사용하지 않는다):
 * <ul>
 *   <li>produce rate — log-end offset 증가율: {@code rate(kafka_topic_partition_current_offset)}</li>
 *   <li>consume rate — committed offset 증가율: {@code rate(kafka_consumergroup_current_offset)}</li>
 *   <li>consumer lag — {@code kafka_consumergroup_lag}</li>
 * </ul>
 * consumergroup 계열은 활성 컨슈머 그룹이 있을 때만 series가 존재한다(없으면 0 반환).
 */
@Service
public class KafkaMetricsQuery {

    private static final Logger log = LoggerFactory.getLogger(KafkaMetricsQuery.class);

    private final boolean enabled;
    private final PrometheusClient client;

    public KafkaMetricsQuery(
            @Value("${prometheus.enabled:false}") boolean enabled,
            PrometheusClient client) {
        this.enabled = enabled;
        this.client = client;
        if (enabled) log.info("[모니터링] Prometheus 메트릭 수집 활성화");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 토픽 produce rate (msg/sec, 2분 rate). 모든 파티션의 log-end offset 증가율 합. */
    public double produceRate(String topic) {
        return client.queryScalar(
                "sum(rate(kafka_topic_partition_current_offset{topic=\"" + topic + "\"}[2m]))");
    }

    /** 토픽 consume rate (msg/sec, 2분 rate). 토픽을 구독한 모든 consumer group의 committed offset 증가율 합. */
    public double consumeRate(String topic) {
        return client.queryScalar(
                "sum(rate(kafka_consumergroup_current_offset{topic=\"" + topic + "\"}[2m]))");
    }

    /**
     * 해당 sink consumer group의 lag 합계 (Kafka Exporter 기반).
     * topic이 아닌 consumergroup으로 필터한다 — 같은 토픽을 구독하는 삭제된 파이프라인의
     * orphan group(예: connect-&lt;old-pid&gt;-sink)이 합산되어 미동기화가 과대 표시되는 것을 방지.
     */
    public long totalLag(String consumerGroup) {
        double val = client.queryScalar(
                "sum(kafka_consumergroup_lag{consumergroup=\"" + consumerGroup + "\"})");
        return Math.max(0L, (long) val);
    }

    /** produce rate 시계열 (timestamp초 → msg/sec). range query. */
    public java.util.Map<Long, Double> produceSeries(String topic, long startSec, long endSec, long stepSec) {
        return client.queryRange(
                "sum(rate(kafka_topic_partition_current_offset{topic=\"" + topic + "\"}[1m]))",
                startSec, endSec, stepSec);
    }

    /** consume rate 시계열 (timestamp초 → msg/sec). range query. */
    public java.util.Map<Long, Double> consumeSeries(String topic, long startSec, long endSec, long stepSec) {
        return client.queryRange(
                "sum(rate(kafka_consumergroup_current_offset{topic=\"" + topic + "\"}[1m]))",
                startSec, endSec, stepSec);
    }

    /**
     * 소스 지연(ms) 시계열 — Debezium MilliSecondsBehindSource(=데이터 전송 시간). server=topic.prefix.
     * 순간 게이지는 부하 배치 타이밍에 따라 크게 튀므로, 스텝 구간 평균(avg_over_time)으로 평활화해
     * 대표 전송시간을 보여준다(폴링 간 들쭉날쭉함도 완화). 데이터 없음(idle) 구간은 -1 유지 → 프론트가 gap 처리.
     */
    public java.util.Map<Long, Double> sourceDelaySeries(String server, long startSec, long endSec, long stepSec) {
        // 평활화 창은 스크랩 간격(~15~30s)보다 충분히 커야 여러 스크랩을 평균내 추세가 보인다(최소 60s).
        long smoothSec = Math.max(60L, stepSec);
        return client.queryRange(
                "max(avg_over_time(debezium_metrics_millisecondsbehindsource{server=\"" + server + "\"}[" + smoothSec + "s]))",
                startSec, endSec, stepSec);
    }

    /**
     * 미동기화 row 추이 — sink consumer group의 lag(미소비 메시지 ≈ 미동기화 row).
     * topic이 아닌 consumergroup으로 필터 — orphan group 합산 방지({@link #totalLag}).
     */
    public java.util.Map<Long, Double> unsyncedSeries(String consumerGroup, long startSec, long endSec, long stepSec) {
        return client.queryRange(
                "sum(kafka_consumergroup_lag{consumergroup=\"" + consumerGroup + "\"})",
                startSec, endSec, stepSec);
    }

    /**
     * 이벤트 타입별 증가분 시계열 — Debezium TotalNumberOf{Create,Update,Delete}EventsSeen.
     * @param op "create" | "update" | "delete"
     */
    public java.util.Map<Long, Double> eventCountSeries(String server, String op, long startSec, long endSec, long stepSec) {
        return client.queryRange(
                "sum(increase(debezium_metrics_totalnumberof" + op + "eventsseen{server=\"" + server + "\"}[" + stepSec + "s]))",
                startSec, endSec, stepSec);
    }
}
