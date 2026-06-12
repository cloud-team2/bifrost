package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 파이프라인 sink connector의 consumer lag를 30초마다 수집하고
 * workspace 임계값 초과 시 event를 발행한다(S1).
 */
@Component
public class KafkaAdminPoller {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminPoller.class);
    private static final long TIMEOUT_SEC = 5L;
    private static final String NONE = "NONE";

    private final AdminClient adminClient;
    private final PipelineRepository pipelineRepository;
    private final WorkspaceSettingsRepository settingsRepository;
    private final EventService eventService;
    private final IncidentService incidentService;
    private final com.bifrost.ops.pipeline.PipelineStatusService pipelineStatusService;

    // consumer group → 직전 알람 레벨 ("NONE" | "WARN" | "ERROR")
    private final ConcurrentHashMap<String, String> lagAlarmState = new ConcurrentHashMap<>();
    // topic → 직전 복제 알람 레벨 ("NONE" | "WARN" | "ERROR") — under-replicated/offline 파티션(#633 Phase 2)
    private final ConcurrentHashMap<String, String> topicAlarmState = new ConcurrentHashMap<>();

    public KafkaAdminPoller(AdminClient adminClient,
                             PipelineRepository pipelineRepository,
                             WorkspaceSettingsRepository settingsRepository,
                             EventService eventService,
                             IncidentService incidentService,
                             com.bifrost.ops.pipeline.PipelineStatusService pipelineStatusService) {
        this.adminClient = adminClient;
        this.pipelineRepository = pipelineRepository;
        this.settingsRepository = settingsRepository;
        this.eventService = eventService;
        this.incidentService = incidentService;
        this.pipelineStatusService = pipelineStatusService;
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 15_000)
    public void poll() {
        List<PipelineEntity> pipelines = pipelineRepository.findAll();
        for (PipelineEntity p : pipelines) {
            try {
                evaluateTopicReplication(p);  // (#633 Phase 2) under-replicated/offline 파티션
            } catch (Exception e) {
                log.debug("토픽 복제 헬스 수집 실패(무시): topic={} cause={}", p.getTopicName(), e.getMessage());
            }
            if (p.getSinkConnectorName() == null) continue;
            String group = "connect-" + p.getSinkConnectorName();
            try {
                long lag = calculateLag(group);
                evaluateLag(group, lag, p);                          // 이벤트(WARNING/CRITICAL/회복)
                pipelineStatusService.applyConsumerLag(p.getId(), lag); // (#559) pipeline status 전이
            } catch (Exception e) {
                log.debug("consumer lag 수집 실패(무시): group={} cause={}", group, e.getMessage());
            }
        }
    }

    /**
     * 파이프라인 토픽의 파티션 복제 헬스를 점검해 인시던트화한다(#633 Phase 2).
     * offline(리더 없음) → ERROR 즉시, under-replicated(ISR &lt; replicas) → WARN(2건/30분 게이팅).
     * edge-trigger: 직전 상태와 다를 때만 발행.
     */
    private void evaluateTopicReplication(PipelineEntity p) throws Exception {
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) return;

        Map<String, TopicDescription> described = adminClient
                .describeTopics(List.of(topic))
                .allTopicNames()
                .get(TIMEOUT_SEC, TimeUnit.SECONDS);
        TopicDescription desc = described.get(topic);
        if (desc == null) return;

        int offline = 0;
        int underReplicated = 0;
        for (TopicPartitionInfo part : desc.partitions()) {
            if (part.leader() == null) {
                offline++;
            } else if (part.isr().size() < part.replicas().size()) {
                underReplicated++;
            }
        }

        String key = IncidentGroupingKeys.topicReplication(topic);
        String prev = topicAlarmState.getOrDefault(topic, NONE);

        if (offline > 0) {
            if (!"ERROR".equals(prev)) {
                String msg = "토픽 '" + topic + "' offline 파티션 " + offline + "개 (리더 없음 — 브로커 장애 가능)";
                incidentService.onThresholdViolation(p.getTenantId(), key, "TOPIC", null, EventLevel.ERROR,
                        "Topic '" + topic + "' offline partitions",
                        "TOPIC_OFFLINE_PARTITIONS", msg, p.getId());
            }
            topicAlarmState.put(topic, "ERROR");
        } else if (underReplicated > 0) {
            if (!"WARN".equals(prev) && !"ERROR".equals(prev)) {
                String msg = "토픽 '" + topic + "' under-replicated 파티션 " + underReplicated + "개 (ISR < replicas)";
                incidentService.onThresholdViolation(p.getTenantId(), key, "TOPIC", null, EventLevel.WARN,
                        "Topic '" + topic + "' under-replicated",
                        "TOPIC_UNDER_REPLICATED", msg, p.getId());
            }
            topicAlarmState.put(topic, "WARN");
        } else {
            if (!NONE.equals(prev)) {
                String msg = "토픽 '" + topic + "' 복제 정상화";
                if (!incidentService.onRecovery(p.getTenantId(), key,
                        "TOPIC_REPLICATION_RECOVERED", msg, p.getId())) {
                    eventService.record(p.getTenantId(), p.getId(), EventLevel.INFO,
                            "TOPIC_REPLICATION_RECOVERED", msg);
                }
            }
            topicAlarmState.put(topic, NONE);
        }
    }

    private long calculateLag(String groupId) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                .listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(TIMEOUT_SEC, TimeUnit.SECONDS);

        if (committed == null || committed.isEmpty()) return 0L;

        Map<TopicPartition, OffsetSpec> latestReqs = committed.keySet().stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = adminClient
                .listOffsets(latestReqs)
                .all()
                .get(TIMEOUT_SEC, TimeUnit.SECONDS);

        long totalLag = 0L;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            ListOffsetsResult.ListOffsetsResultInfo end = endOffsets.get(entry.getKey());
            if (end != null && end.offset() >= 0 && entry.getValue() != null) {
                totalLag += Math.max(0L, end.offset() - entry.getValue().offset());
            }
        }
        return totalLag;
    }

    private void evaluateLag(String group, long lag, PipelineEntity p) {
        WorkspaceSettingsEntity settings = settingsRepository.findById(p.getTenantId())
                .orElse(WorkspaceSettingsEntity.defaults(p.getTenantId()));
        String prev = lagAlarmState.getOrDefault(group, NONE);

        if (lag >= settings.getLagCriticalThreshold()) {
            if (!"ERROR".equals(prev)) {
                String message = "consumer lag 임계 초과: group=" + group + " lag=" + lag;
                incidentService.onThresholdViolation(p.getTenantId(), IncidentGroupingKeys.consumerLag(group),
                        "CONSUMER_GROUP", null, EventLevel.ERROR,
                        "Consumer lag critical: " + group,
                        "CONSUMER_LAG_CRITICAL", message, p.getId());
            }
            lagAlarmState.put(group, "ERROR");
        } else if (lag >= settings.getLagWarningThreshold()) {
            if (!"WARN".equals(prev) && !"ERROR".equals(prev)) {
                eventService.record(p.getTenantId(), p.getId(), EventLevel.WARN,
                        "CONSUMER_LAG_WARNING",
                        "consumer lag 경고: group=" + group + " lag=" + lag);
            }
            lagAlarmState.put(group, "WARN");
        } else {
            if (!NONE.equals(prev)) {
                String message = "consumer lag 정상화: group=" + group + " lag=" + lag;
                if (!incidentService.onRecovery(p.getTenantId(), IncidentGroupingKeys.consumerLag(group),
                        "CONSUMER_LAG_RECOVERED", message, p.getId())) {
                    eventService.record(p.getTenantId(), p.getId(), EventLevel.INFO,
                            "CONSUMER_LAG_RECOVERED", message);
                }
            }
            lagAlarmState.put(group, NONE);
        }
    }
}
