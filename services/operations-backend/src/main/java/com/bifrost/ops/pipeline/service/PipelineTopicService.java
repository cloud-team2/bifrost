package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.pipeline.dto.ConsumerGroupInfo;
import com.bifrost.ops.pipeline.dto.PipelineMetricsResponse;
import com.bifrost.ops.pipeline.dto.TopicInfoResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 파이프라인 토픽/컨슈머 그룹/메트릭 조회(#126). KafkaAdmin(AdminClient) 기반.
 * Kafka에 접근 불가한 환경(로컬 포트포워드 미설정 등)에서는 빈 결과를 반환한다.
 */
@Service
public class PipelineTopicService {

    private static final Logger log = LoggerFactory.getLogger(PipelineTopicService.class);
    private static final long ADMIN_TIMEOUT_SEC = 5L;

    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final WorkspaceAccessGuard accessGuard;
    private final AdminClient adminClient;

    public PipelineTopicService(PipelineRepository pipelineRepository,
                                ConnectorRepository connectorRepository,
                                WorkspaceAccessGuard accessGuard,
                                AdminClient adminClient) {
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.accessGuard = accessGuard;
        this.adminClient = adminClient;
    }

    public TopicInfoResponse topicInfo(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "파이프라인에 토픽이 없습니다");
        }
        try {
            return fetchTopicInfo(topic);
        } catch (Exception e) {
            log.warn("토픽 정보 조회 실패 (Kafka 접근 불가): topic={}, cause={}", topic, e.getMessage());
            return new TopicInfoResponse(topic, 100.0, -1L, List.of());
        }
    }

    public List<ConsumerGroupInfo> consumerGroups(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) return List.of();
        try {
            return fetchConsumerGroups(topic);
        } catch (Exception e) {
            log.warn("Consumer group 조회 실패: topic={}, cause={}", topic, e.getMessage());
            return List.of();
        }
    }

    public PipelineMetricsResponse metrics(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);

        // error_pct: connectors 테이블 기반 (Kafka 불필요)
        List<ConnectorEntity> connectors = connectorRepository.findByPipelineId(id);
        double errorPct = 0.0;
        if (!connectors.isEmpty()) {
            long failed = connectors.stream()
                    .filter(c -> "FAILED".equals(c.getState()) || "PARTIALLY_FAILED".equals(c.getState()))
                    .count();
            errorPct = (double) failed / connectors.size() * 100.0;
        }

        // lag: consumer group 전체 lag
        long lagMessages = 0L;
        String topic = p.getTopicName();
        if (topic != null && !topic.isBlank()) {
            try {
                lagMessages = fetchConsumerGroups(topic).stream()
                        .mapToLong(ConsumerGroupInfo::totalLag)
                        .sum();
            } catch (Exception e) {
                log.debug("메트릭 lag 조회 실패: {}", e.getMessage());
            }
        }

        return new PipelineMetricsResponse(0.0, 0.0, lagMessages, errorPct);
    }

    // ---- private ----

    private TopicInfoResponse fetchTopicInfo(String topic) throws Exception {
        // 1) describe topic → partitions, replicas, isr
        TopicDescription td = adminClient.describeTopics(List.of(topic))
                .allTopicNames().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS).get(topic);
        if (td == null) return new TopicInfoResponse(topic, 100.0, -1L, List.of());

        List<TopicPartitionInfo> partitions = td.partitions();

        // ISR 비율
        int totalReplicas = partitions.stream().mapToInt(p -> p.replicas().size()).sum();
        int totalIsr = partitions.stream().mapToInt(p -> p.isr().size()).sum();
        double isrPct = totalReplicas > 0 ? (double) totalIsr / totalReplicas * 100.0 : 100.0;

        // 2) begin/end offsets
        Map<TopicPartition, OffsetSpec> beginReqs = new HashMap<>();
        Map<TopicPartition, OffsetSpec> endReqs = new HashMap<>();
        for (TopicPartitionInfo tpi : partitions) {
            TopicPartition tp = new TopicPartition(topic, tpi.partition());
            beginReqs.put(tp, OffsetSpec.earliest());
            endReqs.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> begins =
                adminClient.listOffsets(beginReqs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                adminClient.listOffsets(endReqs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

        // 3) retention config
        ConfigResource cr = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config cfg = adminClient.describeConfigs(List.of(cr))
                .all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS).get(cr);
        long retentionMs = -1L;
        if (cfg != null) {
            ConfigEntry entry = cfg.get("retention.ms");
            if (entry != null && entry.value() != null) {
                try { retentionMs = Long.parseLong(entry.value()); } catch (NumberFormatException ignored) {}
            }
        }

        List<TopicInfoResponse.PartitionDetail> details = partitions.stream().map(tpi -> {
            TopicPartition tp = new TopicPartition(topic, tpi.partition());
            String leader = tpi.leader() != null ? "broker-" + tpi.leader().id() : "unknown";
            long begin = begins.getOrDefault(tp, dummyOffset(0)).offset();
            long end = ends.getOrDefault(tp, dummyOffset(0)).offset();
            return new TopicInfoResponse.PartitionDetail(tpi.partition(), leader, begin, end);
        }).toList();

        return new TopicInfoResponse(topic, isrPct, retentionMs, details);
    }

    private List<ConsumerGroupInfo> fetchConsumerGroups(String topic) throws Exception {
        // 1) 전체 consumer group 목록
        List<String> allGroupIds = adminClient.listConsumerGroups()
                .all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)
                .stream().map(ConsumerGroupListing::groupId).toList();

        if (allGroupIds.isEmpty()) return List.of();

        // 2) 해당 토픽 구독 그룹 필터 (committed offsets로 판별)
        List<String> relevant = new ArrayList<>();
        for (String gid : allGroupIds) {
            try {
                Map<TopicPartition, OffsetAndMetadata> offsets = adminClient
                        .listConsumerGroupOffsets(gid)
                        .partitionsToOffsetAndMetadata()
                        .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (offsets.keySet().stream().anyMatch(tp -> tp.topic().equals(topic))) {
                    relevant.add(gid);
                }
            } catch (Exception ignored) {}
        }
        if (relevant.isEmpty()) return List.of();

        // 3) describe groups → state, members
        Map<String, ConsumerGroupDescription> descs = adminClient
                .describeConsumerGroups(relevant)
                .all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

        // 4) end offsets for lag
        List<TopicPartition> tps = descs.values().stream()
                .flatMap(d -> d.members().stream())
                .flatMap(m -> m.assignment().topicPartitions().stream())
                .filter(tp -> tp.topic().equals(topic))
                .distinct().toList();

        Map<TopicPartition, Long> endOffsets = new HashMap<>();
        if (!tps.isEmpty()) {
            Map<TopicPartition, OffsetSpec> endReqs = tps.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            adminClient.listOffsets(endReqs).all()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .forEach((tp, info) -> endOffsets.put(tp, info.offset()));
        }

        // 5) 조합
        List<ConsumerGroupInfo> result = new ArrayList<>();
        for (String gid : relevant) {
            ConsumerGroupDescription desc = descs.get(gid);
            if (desc == null) continue;

            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(gid)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            List<ConsumerGroupInfo.PartitionOffset> partOffsets = new ArrayList<>();
            long totalLag = 0L;

            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                if (!e.getKey().topic().equals(topic)) continue;
                long committedOffset = e.getValue().offset();
                long endOffset = endOffsets.getOrDefault(e.getKey(), committedOffset);
                long lag = Math.max(0, endOffset - committedOffset);
                totalLag += lag;

                String member = desc.members().stream()
                        .filter(m -> m.assignment().topicPartitions().contains(e.getKey()))
                        .map(MemberDescription::consumerId)
                        .findFirst().orElse(null);

                partOffsets.add(new ConsumerGroupInfo.PartitionOffset(
                        e.getKey().partition(), member, committedOffset, endOffset));
            }

            result.add(new ConsumerGroupInfo(
                    gid,
                    desc.state().toString(),
                    desc.members().size(),
                    totalLag,
                    -1L,
                    partOffsets));
        }
        return result;
    }

    private PipelineEntity loadPipeline(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        return pipelineRepository.findByIdAndTenantId(id, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND,
                        "파이프라인을 찾을 수 없습니다"));
    }

    private static ListOffsetsResult.ListOffsetsResultInfo dummyOffset(long offset) {
        return new ListOffsetsResult.ListOffsetsResultInfo(offset, -1L, java.util.Optional.empty());
    }
}
