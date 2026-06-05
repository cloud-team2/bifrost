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

    /** 토픽 전체 consumer group lag 합계 (Kafka Exporter 기반). */
    public long totalLag(String topic) {
        double val = client.queryScalar(
                "sum(kafka_consumergroup_lag{topic=\"" + topic + "\"})");
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

    /** 소스 지연(ms) 시계열 — Debezium MilliSecondsBehindSource. server=Debezium topic.prefix. */
    public java.util.Map<Long, Double> sourceDelaySeries(String server, long startSec, long endSec, long stepSec) {
        return client.queryRange(
                "max(debezium_metrics_millisecondsbehindsource{server=\"" + server + "\"})",
                startSec, endSec, stepSec);
    }

    /** 미동기화 row 추이 — consumer lag(미소비 메시지 ≈ 미동기화 row). */
    public java.util.Map<Long, Double> unsyncedSeries(String topic, long startSec, long endSec, long stepSec) {
        return client.queryRange(
                "sum(kafka_consumergroup_lag{topic=\"" + topic + "\"})",
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
