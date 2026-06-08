package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.ConsumerLagResult;
import com.bifrost.ops.internalops.dto.IncidentSummaryResult;
import com.bifrost.ops.internalops.dto.LogSearchResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent read tool — consumer lag, logs, incident summary.
 *
 * <p>search_logs / get_incident_summary는 upstream 의존성(이성민 monitoring/incident read model)
 * 미완료로 stub을 반환한다. 통합 시 해당 메서드만 교체한다.
 */
@RestController
@RequestMapping("/internal/ops")
public class InternalOpsObservabilityController {

    private static final Logger log = LoggerFactory.getLogger(InternalOpsObservabilityController.class);
    private static final long ADMIN_TIMEOUT_SEC = 5L;

    private final AdminClient adminClient;

    public InternalOpsObservabilityController(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    /** get_consumer_lag — consumer group 전체 lag 합계. Kafka 미연결 시 lag=0 반환. */
    @GetMapping("/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag")
    public ResponseEntity<OpsEnvelope<ConsumerLagResult>> consumerLag(
            @PathVariable String projectId,
            @PathVariable String consumerGroup,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        long totalLag = fetchLag(consumerGroup);
        String source = totalLag >= 0 ? "kafka-admin" : "unavailable";
        ConsumerLagResult result = new ConsumerLagResult(consumerGroup, Math.max(0, totalLag), source);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_consumer_lag", result));
    }

    /** search_logs — stub. 이성민 log source 통합 후 교체. */
    @PostMapping("/projects/{projectId}/observability/logs/search")
    public ResponseEntity<OpsEnvelope<LogSearchResult>> searchLogs(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "search_logs", LogSearchResult.stub()));
    }

    /** get_incident_summary — stub. 이성민 incident read model 통합 후 교체. */
    @GetMapping("/incidents/{incidentId}/summary")
    public ResponseEntity<OpsEnvelope<IncidentSummaryResult>> incidentSummary(
            @PathVariable String incidentId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_incident_summary",
                IncidentSummaryResult.stub(incidentId)));
    }

    private long fetchLag(String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            if (committed.isEmpty()) return 0L;

            Map<TopicPartition, OffsetSpec> endReqs = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = adminClient
                    .listOffsets(endReqs)
                    .all()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            long lag = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                long end = ends.containsKey(e.getKey()) ? ends.get(e.getKey()).offset() : 0L;
                lag += Math.max(0L, end - e.getValue().offset());
            }
            return lag;
        } catch (Exception e) {
            log.warn("[InternalOps] consumer lag 조회 실패 (Kafka 미연결): group={}, cause={}", groupId, e.getMessage());
            return -1L;
        }
    }
}
