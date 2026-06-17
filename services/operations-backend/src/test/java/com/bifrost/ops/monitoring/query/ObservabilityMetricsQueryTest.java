package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.internalops.dto.MetricsResult;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ObservabilityMetricsQueryTest {

    private final PrometheusClient client = mock(PrometheusClient.class);

    @Test
    void disabledReturnsStubWithoutCallingPrometheus() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(false, client, "platform-kafka");

        MetricsResult result = query.query(workspace(), "pipeline_lag_seconds", "last_30m");

        assertThat(result.metric()).isEqualTo("pipeline_lag_seconds");
        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.summary()).contains("Prometheus 비활성화");
        verifyNoInteractions(client);
    }

    @Test
    void unknownMetricReturnsStubEvenWhenEnabled() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");

        MetricsResult result = query.query(workspace(), "cpu_percent", "last_30m");

        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.summary()).contains("지원하지 않는 metric");
        verifyNoInteractions(client);
    }

    @Test
    void knownMetricQueriesPrometheusRangeAndMapsPoints() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        TreeMap<Long, Double> series = new TreeMap<>();
        series.put(1_749_513_600L, 10.0);
        series.put(1_749_513_660L, 12.5);
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(series);

        MetricsResult result = query.query(workspace(), "pipeline_lag_seconds", "last_30m");

        assertThat(result.metric()).isEqualTo("pipeline_lag_seconds");
        assertThat(result.dataPoints()).hasSize(2);
        // 시간 오름차순 + latest 값 요약.
        assertThat(result.dataPoints().getFirst().value()).isEqualTo(10.0);
        assertThat(result.dataPoints().getLast().value()).isEqualTo(12.5);
        assertThat(result.summary()).contains("latest=12.500");

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(client).queryRange(promql.capture(), anyLong(), anyLong(), anyLong());
        assertThat(promql.getValue()).isEqualTo(
                "sum(kafka_consumergroup_lag{namespace=\"platform-kafka\","
                        + "topic=~\"(cdc|eda)\\\\.table\\\\.proj-001\\\\..*\"})");
        assertThat(promql.getValue()).doesNotContain("namespace=\"proj-001\"");
    }

    @Test
    void lagP95SummaryOnlyAddsTrendEvidenceWhenRangeIncreases() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        TreeMap<Long, Double> series = new TreeMap<>();
        series.put(1_749_513_600L, 42.0);
        series.put(1_749_513_660L, 12345.0);
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(series);

        MetricsResult result = query.query(workspace(), "consumer_lag_p95", "last_30m");

        assertThat(result.summary()).contains("lag p95 증가");
    }

    @Test
    void commitRateSummaryAddsSlowingEvidenceWhenRangeDecreases() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        TreeMap<Long, Double> series = new TreeMap<>();
        series.put(1_749_513_600L, 120.0);
        series.put(1_749_513_660L, 5.0);
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(series);

        MetricsResult result = query.query(workspace(), "consumer_commit_rate_per_sec", "last_30m");

        assertThat(result.summary()).contains("offset progression 둔화 commit rate 감소");
    }

    @Test
    void promqlEscapesProjectKeyForRegexLabelMatching() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(new TreeMap<>());

        query.query(workspace("proj.001+blue"), "pipeline_lag_seconds", "last_30m");

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(client).queryRange(promql.capture(), anyLong(), anyLong(), anyLong());
        assertThat(promql.getValue()).isEqualTo(
                "sum(kafka_consumergroup_lag{namespace=\"platform-kafka\","
                        + "topic=~\"(cdc|eda)\\\\.table\\\\.proj\\\\.001\\\\+blue\\\\..*\"})");
    }

    @Test
    void liveBackedCatalogMetricMappingsUseKafkaExporterAndDebeziumMetrics() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(new TreeMap<>());

        query.query(workspace(), "consumer_lag_p95", "last_30m");
        query.query(workspace(), "consumer_commit_rate_per_sec", "last_30m");
        query.query(workspace(), "topic_ingress_messages_per_sec", "last_30m");
        query.query(workspace(), "source_freshness_delay_ms", "last_30m");
        query.query(workspace(), "source_watermark_delay_ms", "last_30m");
        query.query(workspace(), "source_event_rate_per_sec", "last_30m");

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(client, times(6)).queryRange(promql.capture(), anyLong(), anyLong(), anyLong());
        assertThat(promql.getAllValues()).containsExactly(
                "quantile(0.95, kafka_consumergroup_lag{namespace=\"platform-kafka\","
                        + "topic=~\"(cdc|eda)\\\\.table\\\\.proj-001\\\\..*\"})",
                "sum(rate(kafka_consumergroup_current_offset{namespace=\"platform-kafka\","
                        + "topic=~\"(cdc|eda)\\\\.table\\\\.proj-001\\\\..*\"}[5m]))",
                "sum(rate(kafka_topic_partition_current_offset{namespace=\"platform-kafka\","
                        + "topic=~\"(cdc|eda)\\\\.table\\\\.proj-001\\\\..*\"}[5m]))",
                "max(debezium_metrics_millisecondsbehindsource{namespace=\"platform-kafka\","
                        + "server=~\"cdc\\\\.table\\\\.proj-001\\\\..*\"})",
                "max(debezium_metrics_millisecondsbehindsource{namespace=\"platform-kafka\","
                        + "server=~\"cdc\\\\.table\\\\.proj-001\\\\..*\"})",
                "sum(rate(debezium_metrics_totalnumberofeventsseen{namespace=\"platform-kafka\","
                        + "server=~\"cdc\\\\.table\\\\.proj-001\\\\..*\"}[5m]))");
    }

    @Test
    void brokerResourceMetricMappingsUseKafkaBrokerPodContainerMetrics() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(new TreeMap<>());

        query.query(workspace(), "broker_cpu_cores", "last_30m");
        query.query(workspace(), "broker_memory_working_set_bytes", "last_30m");
        query.query(workspace(), "broker_network_receive_bytes_per_sec", "last_30m");
        query.query(workspace(), "broker_network_transmit_bytes_per_sec", "last_30m");
        query.query(workspace(), "broker_fs_read_bytes_per_sec", "last_30m");
        query.query(workspace(), "broker_fs_write_bytes_per_sec", "last_30m");

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(client, times(6)).queryRange(promql.capture(), anyLong(), anyLong(), anyLong());
        assertThat(promql.getAllValues()).containsExactly(
                "sum(rate(container_cpu_usage_seconds_total{namespace=\"platform-kafka\","
                        + "pod=~\"platform-kafka-kafka-[0-9]+\"}[5m]))",
                "sum(container_memory_working_set_bytes{namespace=\"platform-kafka\","
                        + "pod=~\"platform-kafka-kafka-[0-9]+\"})",
                "sum(rate(container_network_receive_bytes_total{namespace=\"platform-kafka\","
                        + "pod=~\"platform-kafka-kafka-[0-9]+\"}[5m]))",
                "sum(rate(container_network_transmit_bytes_total{namespace=\"platform-kafka\","
                        + "pod=~\"platform-kafka-kafka-[0-9]+\"}[5m]))",
                "sum(rate(container_fs_reads_bytes_total{namespace=\"platform-kafka\","
                        + "pod=~\"platform-kafka-kafka-[0-9]+\"}[5m]))",
                "sum(rate(container_fs_writes_bytes_total{namespace=\"platform-kafka\","
                        + "pod=~\"platform-kafka-kafka-[0-9]+\"}[5m]))");
    }

    @Test
    void promqlFallsBackToWorkspaceIdWhenNamespaceIsBlank() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client, "platform-kafka");
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(new TreeMap<>());
        UUID workspaceId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        query.query(workspace(workspaceId, " "), "pipeline_lag_seconds", "last_30m");

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(client).queryRange(promql.capture(), anyLong(), anyLong(), anyLong());
        assertThat(promql.getValue()).isEqualTo(
                "sum(kafka_consumergroup_lag{namespace=\"platform-kafka\","
                        + "topic=~\"(cdc|eda)\\\\.table\\\\.11111111-2222-3333-4444-555555555555\\\\..*\"})");
    }

    private static WorkspaceEntity workspace() {
        return workspace("proj-001");
    }

    private static WorkspaceEntity workspace(String namespace) {
        return workspace(UUID.randomUUID(), namespace);
    }

    private static WorkspaceEntity workspace(UUID id, String namespace) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setName(namespace);
        workspace.setNamespace(namespace);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }
}
