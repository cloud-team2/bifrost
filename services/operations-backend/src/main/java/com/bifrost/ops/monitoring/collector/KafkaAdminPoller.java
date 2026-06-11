package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
    private final com.bifrost.ops.pipeline.PipelineStatusService pipelineStatusService;

    // consumer group → 직전 알람 레벨 ("NONE" | "WARN" | "ERROR")
    private final ConcurrentHashMap<String, String> lagAlarmState = new ConcurrentHashMap<>();

    public KafkaAdminPoller(AdminClient adminClient,
                             PipelineRepository pipelineRepository,
                             WorkspaceSettingsRepository settingsRepository,
                             EventService eventService,
                             com.bifrost.ops.pipeline.PipelineStatusService pipelineStatusService) {
        this.adminClient = adminClient;
        this.pipelineRepository = pipelineRepository;
        this.settingsRepository = settingsRepository;
        this.eventService = eventService;
        this.pipelineStatusService = pipelineStatusService;
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 15_000)
    public void poll() {
        List<PipelineEntity> pipelines = pipelineRepository.findAll();
        for (PipelineEntity p : pipelines) {
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
                eventService.record(p.getTenantId(), p.getId(), EventLevel.ERROR,
                        "CONSUMER_LAG_CRITICAL",
                        "consumer lag 임계 초과: group=" + group + " lag=" + lag);
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
                eventService.record(p.getTenantId(), p.getId(), EventLevel.INFO,
                        "CONSUMER_LAG_RECOVERED",
                        "consumer lag 정상화: group=" + group + " lag=" + lag);
            }
            lagAlarmState.put(group, NONE);
        }
    }
}
