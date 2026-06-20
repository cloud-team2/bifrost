package com.bifrost.ops.monitoring.sli;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.monitoring.dto.SliDefinitionResponse;
import com.bifrost.ops.monitoring.dto.SliMeasurementResponse;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 사용자 영향 중심 SLI 정의와 현재 측정값 산출 서비스(#891). */
@Service
public class UserImpactSliService {

    private static final Logger log = LoggerFactory.getLogger(UserImpactSliService.class);
    private static final int DEFAULT_WINDOW_MINUTES = 30;
    private static final int MIN_WINDOW_MINUTES = 5;
    private static final int MAX_WINDOW_MINUTES = 360;
    private static final double FRESHNESS_DELAY_THRESHOLD_MS = 300_000.0;
    private static final double E2E_DELAY_THRESHOLD_MS = 60_000.0;

    private final boolean prometheusEnabled;
    private final String kafkaNamespace;
    private final PrometheusClient prometheusClient;
    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;

    public UserImpactSliService(@Value("${prometheus.enabled:false}") boolean prometheusEnabled,
                                @Value("${kafka-cluster.namespace:platform-kafka}") String kafkaNamespace,
                                PrometheusClient prometheusClient,
                                WorkspaceRepository workspaceRepository,
                                PipelineRepository pipelineRepository) {
        this.prometheusEnabled = prometheusEnabled;
        this.kafkaNamespace = nonBlankOrDefault(kafkaNamespace, "platform-kafka");
        this.prometheusClient = prometheusClient;
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
    }

    public List<SliDefinitionResponse> definitions() {
        return Arrays.stream(UserImpactSliType.values())
                .map(SliDefinitionResponse::from)
                .toList();
    }

    public List<SliMeasurementResponse> measurements(UUID workspaceId, Integer windowMinutes) {
        int window = normalizeWindow(windowMinutes);
        Instant measuredAt = Instant.now();
        WorkspaceEntity workspace = workspace(workspaceId);
        return Arrays.stream(UserImpactSliType.values())
                .map(type -> measure(workspace, type, window, measuredAt))
                .toList();
    }

    public SliMeasurementResponse measurement(UUID workspaceId, UserImpactSliType type, Integer windowMinutes) {
        int window = normalizeWindow(windowMinutes);
        return measure(workspace(workspaceId), type, window, Instant.now());
    }

    private SliMeasurementResponse measure(WorkspaceEntity workspace,
                                           UserImpactSliType type,
                                           int windowMinutes,
                                           Instant measuredAt) {
        if (type == UserImpactSliType.PROVISIONING_SUCCESS_RATE) {
            return provisioningSuccess(workspace.getId(), windowMinutes, measuredAt);
        }
        if (!prometheusEnabled) {
            return unknown(type, windowMinutes, measuredAt, "prometheus",
                    "Prometheus 비활성화로 현재 SLI 측정값을 산출하지 않았습니다");
        }
        try {
            return switch (type) {
                case DATA_FRESHNESS -> prometheusRatio(
                        type,
                        sourceEventsGoodWhenDelayBelow(workspace, windowMinutes, FRESHNESS_DELAY_THRESHOLD_MS),
                        sourceEvents(workspace, windowMinutes),
                        windowMinutes,
                        measuredAt,
                        "prometheus");
                case END_TO_END_LATENCY -> prometheusRatio(
                        type,
                        sourceEventsGoodWhenDelayBelow(workspace, windowMinutes, E2E_DELAY_THRESHOLD_MS),
                        sourceEvents(workspace, windowMinutes),
                        windowMinutes,
                        measuredAt,
                        "prometheus");
                case PROCESSING_SUCCESS_RATE -> processingSuccess(workspace, windowMinutes, measuredAt);
                case DATA_COMPLETENESS -> dataCompleteness(workspace, windowMinutes, measuredAt);
                case PROVISIONING_SUCCESS_RATE -> provisioningSuccess(workspace.getId(), windowMinutes, measuredAt);
            };
        } catch (RestClientException | IllegalArgumentException e) {
            log.debug("SLI Prometheus 조회 실패: type={} workspace={} cause={}",
                    type, workspace.getId(), e.getMessage());
            return unknown(type, windowMinutes, measuredAt, "prometheus",
                    "Prometheus 조회 실패로 현재 SLI 측정값을 산출하지 못했습니다");
        }
    }

    private SliMeasurementResponse processingSuccess(WorkspaceEntity workspace,
                                                     int windowMinutes,
                                                     Instant measuredAt) {
        String events = sourceEvents(workspace, windowMinutes);
        String failures = "sum(increase(debezium_metrics_numberoferroneousevents{namespace=\""
                + labelValue(kafkaNamespace) + "\",server=~\"" + debeziumServerRegex(workspace) + "\"}["
                + windowMinutes + "m]))";
        double total = prometheusClient.queryScalar(events);
        double failed = prometheusClient.queryScalar(failures);
        double good = Math.max(0.0, total - Math.max(0.0, failed));
        return ratio(UserImpactSliType.PROCESSING_SUCCESS_RATE, good, total, windowMinutes, measuredAt, "prometheus", null);
    }

    private SliMeasurementResponse dataCompleteness(WorkspaceEntity workspace,
                                                    int windowMinutes,
                                                    Instant measuredAt) {
        String namespace = labelValue(kafkaNamespace);
        String topicRegex = projectTopicRegex(workspace);
        double ingress = prometheusClient.queryScalar(
                "sum(increase(kafka_topic_partition_current_offset{namespace=\"" + namespace
                        + "\",topic=~\"" + topicRegex + "\"}[" + windowMinutes + "m]))");
        double committed = prometheusClient.queryScalar(
                "sum(increase(kafka_consumergroup_current_offset{namespace=\"" + namespace
                        + "\",topic=~\"" + topicRegex + "\"}[" + windowMinutes + "m]))");
        return ratio(UserImpactSliType.DATA_COMPLETENESS, Math.min(committed, ingress), ingress,
                windowMinutes, measuredAt, "prometheus", null);
    }

    private SliMeasurementResponse prometheusRatio(UserImpactSliType type,
                                                  String goodQuery,
                                                  String totalQuery,
                                                  int windowMinutes,
                                                  Instant measuredAt,
                                                  String source) {
        double total = prometheusClient.queryScalar(totalQuery);
        double good = Math.min(prometheusClient.queryScalar(goodQuery), total);
        return ratio(type, good, total, windowMinutes, measuredAt, source, null);
    }

    private SliMeasurementResponse provisioningSuccess(UUID workspaceId, int windowMinutes, Instant measuredAt) {
        long total = pipelineRepository.countByTenantId(workspaceId);
        long active = pipelineRepository.countByTenantIdAndStatus(workspaceId, PipelineLifecycle.ACTIVE);
        return ratio(UserImpactSliType.PROVISIONING_SUCCESS_RATE, active, total,
                windowMinutes, measuredAt, "database", null);
    }

    private SliMeasurementResponse ratio(UserImpactSliType type,
                                         double good,
                                         double total,
                                         int windowMinutes,
                                         Instant measuredAt,
                                         String source,
                                         String note) {
        if (total <= 0.0) {
            return unknown(type, windowMinutes, measuredAt, source,
                    note == null ? "window 내 total_event가 없어 SLI를 판정하지 않았습니다" : note);
        }
        double boundedGood = Math.max(0.0, Math.min(good, total));
        double value = boundedGood / total;
        return SliMeasurementResponse.of(
                type,
                boundedGood,
                total,
                value,
                status(type, value),
                windowMinutes,
                measuredAt,
                source,
                note);
    }

    private SliMeasurementResponse unknown(UserImpactSliType type,
                                           int windowMinutes,
                                           Instant measuredAt,
                                           String source,
                                           String note) {
        return SliMeasurementResponse.of(
                type, 0.0, 0.0, null, UserImpactSliStatus.UNKNOWN,
                windowMinutes, measuredAt, source, note);
    }

    private static UserImpactSliStatus status(UserImpactSliType type, double ratio) {
        if (ratio >= type.targetRatio()) {
            return UserImpactSliStatus.GOOD;
        }
        if (ratio >= type.targetRatio() * 0.99) {
            return UserImpactSliStatus.WARNING;
        }
        return UserImpactSliStatus.CRITICAL;
    }

    private WorkspaceEntity workspace(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "workspace not found: " + workspaceId));
    }

    private String sourceEventsGoodWhenDelayBelow(WorkspaceEntity workspace, int windowMinutes, double thresholdMs) {
        String total = sourceEvents(workspace, windowMinutes);
        String delay = "max(debezium_metrics_millisecondsbehindsource{namespace=\""
                + labelValue(kafkaNamespace) + "\",server=~\"" + debeziumServerRegex(workspace) + "\"})";
        return total + " unless on() (" + delay + " > " + format(thresholdMs) + ")";
    }

    private String sourceEvents(WorkspaceEntity workspace, int windowMinutes) {
        return "sum(increase(debezium_metrics_totalnumberofeventsseen{namespace=\""
                + labelValue(kafkaNamespace) + "\",server=~\"" + debeziumServerRegex(workspace) + "\"}["
                + windowMinutes + "m]))";
    }

    private String projectTopicRegex(WorkspaceEntity workspace) {
        return "(cdc|eda)\\\\.table\\\\." + regexLabelValue(projectKey(workspace)) + "\\\\..*";
    }

    private String debeziumServerRegex(WorkspaceEntity workspace) {
        return "cdc\\\\.table\\\\." + regexLabelValue(projectKey(workspace)) + "\\\\..*";
    }

    private static String projectKey(WorkspaceEntity workspace) {
        return workspace.getNamespace() != null && !workspace.getNamespace().isBlank()
                ? workspace.getNamespace()
                : workspace.getId().toString();
    }

    private static int normalizeWindow(Integer raw) {
        int value = raw == null ? DEFAULT_WINDOW_MINUTES : raw;
        return Math.max(MIN_WINDOW_MINUTES, Math.min(value, MAX_WINDOW_MINUTES));
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

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
