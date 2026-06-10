package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.internalops.dto.MetricsResult;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ObservabilityMetricsQueryTest {

    private final PrometheusClient client = mock(PrometheusClient.class);

    @Test
    void disabledReturnsStubWithoutCallingPrometheus() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(false, client);

        MetricsResult result = query.query(workspace(), "pipeline_lag_seconds", "last_30m");

        assertThat(result.metric()).isEqualTo("pipeline_lag_seconds");
        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.summary()).contains("Prometheus 비활성화");
        verifyNoInteractions(client);
    }

    @Test
    void unknownMetricReturnsStubEvenWhenEnabled() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client);

        MetricsResult result = query.query(workspace(), "cpu_percent", "last_30m");

        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.summary()).contains("지원하지 않는 metric");
        verifyNoInteractions(client);
    }

    @Test
    void knownMetricQueriesPrometheusRangeAndMapsPoints() {
        ObservabilityMetricsQuery query = new ObservabilityMetricsQuery(true, client);
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
    }

    private static WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(UUID.randomUUID());
        workspace.setName("proj-001");
        workspace.setNamespace("proj-001");
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }
}
