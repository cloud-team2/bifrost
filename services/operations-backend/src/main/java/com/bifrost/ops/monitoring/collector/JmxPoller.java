package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Prometheus 경유로 Kafka Connect worker JVM 메트릭을 60초마다 수집한다(S1).
 * prometheus.enabled=false(기본값)이면 동작하지 않는다.
 */
@Component
public class JmxPoller {

    private static final Logger log = LoggerFactory.getLogger(JmxPoller.class);

    private final PrometheusClient prometheusClient;
    private final boolean prometheusEnabled;

    public JmxPoller(PrometheusClient prometheusClient,
                     @Value("${prometheus.enabled:false}") boolean prometheusEnabled) {
        this.prometheusClient = prometheusClient;
        this.prometheusEnabled = prometheusEnabled;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void poll() {
        if (!prometheusEnabled) return;
        try {
            double heapUsed = prometheusClient.queryScalar(
                    "sum(jvm_memory_used_bytes{job=~\"kafka.*\",area=\"heap\"})");
            double heapMax = prometheusClient.queryScalar(
                    "sum(jvm_memory_max_bytes{job=~\"kafka.*\",area=\"heap\"})");
            double cpuUsage = prometheusClient.queryScalar(
                    "avg(process_cpu_usage{job=~\"kafka.*\"})");
            double gcPause = prometheusClient.queryScalar(
                    "sum(rate(jvm_gc_pause_seconds_sum{job=~\"kafka.*\"}[1m]))");

            double heapPct = heapMax > 0 ? heapUsed / heapMax * 100.0 : 0.0;
            log.debug("[JMX] worker JVM heap={}% cpu={} gcPause={}/s",
                    String.format("%.1f", heapPct),
                    String.format("%.3f", cpuUsage),
                    String.format("%.3f", gcPause));
        } catch (RestClientException e) {
            log.debug("Prometheus JMX 메트릭 조회 실패(무시): {}", e.getMessage());
        }
    }
}
