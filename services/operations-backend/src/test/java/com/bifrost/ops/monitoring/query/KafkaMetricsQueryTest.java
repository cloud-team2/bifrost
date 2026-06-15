package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaMetricsQueryTest {

    @Mock private PrometheusClient prometheusClient;

    @Test
    void sourceErrorRateUsesDebeziumErroneousEventsCounter() {
        KafkaMetricsQuery query = new KafkaMetricsQuery(true, prometheusClient);
        when(prometheusClient.queryScalarOrNull(
                "sum(increase(debezium_metrics_totalnumberofeventsseen{server=\"orders\"}[5m]))"))
                .thenReturn(100.0);
        when(prometheusClient.queryScalarOrNull(
                "sum(increase(debezium_metrics_numberoferroneousevents{server=\"orders\"}[5m]))"))
                .thenReturn(3.0);

        assertThat(query.sourceErrorRatePct("orders")).isEqualTo(3.0);
    }

    @Test
    void sourceErrorRateIsUnknownWhenFailureCounterIsMissing() {
        KafkaMetricsQuery query = new KafkaMetricsQuery(true, prometheusClient);
        when(prometheusClient.queryScalarOrNull(
                "sum(increase(debezium_metrics_totalnumberofeventsseen{server=\"orders\"}[5m]))"))
                .thenReturn(100.0);
        when(prometheusClient.queryScalarOrNull(
                "sum(increase(debezium_metrics_numberoferroneousevents{server=\"orders\"}[5m]))"))
                .thenReturn(null);

        assertThat(query.sourceErrorRatePct("orders")).isNull();
    }
}
