package com.bifrost.ops.pipeline.dto;

import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;

import java.time.Instant;
import java.util.List;

/**
 * 파이프라인 커넥터 응답(#107). 상세 페이지 Connector 탭용.
 *
 * <p>{@code state}/{@code lastError}/{@code updatedAt}는 {@code KafkaConnectorWatcher}가
 * KafkaConnector CR 상태를 받아 갱신한 값이다(생성 직후 watcher 반영 전에는 state가 null일 수 있다).
 * 자격증명 등 비밀값은 포함하지 않는다.
 */
public record ConnectorResponse(
    String name,
    String kind,
    String connectorClass,
    String state,
    int tasksMax,
    String lastError,
    Instant lastErrorAt,
    Instant updatedAt,
    Double errorRatePct,
    Double pollBatchAvg,
    Double pollBatchMax,
    Long retriesTotal,
    Double recordsPerSec,
    List<MetricPoint> recordsPerSecSeries
) {
    public static ConnectorResponse from(ConnectorEntity c) {
        return from(c, ConnectorMetrics.empty());
    }

    public static ConnectorResponse from(ConnectorEntity c, ConnectorMetrics metrics) {
        return new ConnectorResponse(
            c.getCrName(),
            c.getKind().name().toLowerCase(),
            c.getConnectorClass(),
            c.getState(),
            c.getTasksMax(),
            c.getLastError(),
            hasText(c.getLastError()) ? c.getUpdatedAt() : null,
            c.getUpdatedAt(),
            metrics.errorRatePct(),
            metrics.pollBatchAvg(),
            metrics.pollBatchMax(),
            metrics.retriesTotal(),
            metrics.recordsPerSec(),
            metrics.recordsPerSecSeries()
        );
    }

    public record ConnectorMetrics(
            Double errorRatePct,
            Double pollBatchAvg,
            Double pollBatchMax,
            Long retriesTotal,
            Double recordsPerSec,
            List<MetricPoint> recordsPerSecSeries
    ) {
        public static ConnectorMetrics empty() {
            return new ConnectorMetrics(null, null, null, null, null, List.of());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
