package com.bifrost.ops.pipeline.kafka;

import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 파이프라인 토픽 offset 스냅샷 수집기(#126).
 *
 * <p>30초마다 활성 파이프라인의 토픽 end offset + consumer committed offset을 AdminClient로
 * 수집해 {@link OffsetSnapshotStore}에 기록한다. API 스레드 블로킹 없이 produce/consume rate를
 * 제공하기 위한 백그라운드 컬렉터다. Kafka 미접근 시 조용히 skip한다.
 */
@Component
public class OffsetSnapshotCollector {

    private static final Logger log = LoggerFactory.getLogger(OffsetSnapshotCollector.class);
    private static final long TIMEOUT_SEC = 5L;

    private final PipelineRepository pipelineRepository;
    private final AdminClient adminClient;
    private final OffsetSnapshotStore store;

    public OffsetSnapshotCollector(PipelineRepository pipelineRepository,
                                   AdminClient adminClient,
                                   OffsetSnapshotStore store) {
        this.pipelineRepository = pipelineRepository;
        this.adminClient = adminClient;
        this.store = store;
    }

    /** 5초 후 최초 수집, 이후 30초 간격. initialDelay로 앱 기동 완료 후 실행. */
    @Scheduled(fixedRate = 30_000, initialDelay = 5_000)
    public void collect() {
        List<String> topics = pipelineRepository.findAllActiveTopics();
        if (topics.isEmpty()) return;

        for (String topic : topics) {
            try {
                collectOne(topic);
            } catch (Exception e) {
                log.debug("offset snapshot 수집 실패 (Kafka 미접근): topic={}", topic);
            }
        }
        log.debug("offset snapshot 수집 완료: {} 토픽", topics.size());
    }

    private void collectOne(String topic) throws Exception {
        // 1) 파티션 목록
        TopicDescription td = adminClient.describeTopics(List.of(topic))
                .allTopicNames().get(TIMEOUT_SEC, TimeUnit.SECONDS).get(topic);
        if (td == null) return;

        List<TopicPartition> tps = td.partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

        // 2) end offsets
        Map<TopicPartition, OffsetSpec> endReqs = tps.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
        Map<TopicPartition, Long> endOffsets = adminClient.listOffsets(endReqs)
                .all().get(TIMEOUT_SEC, TimeUnit.SECONDS)
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().offset()));

        // 3) committed offsets (파티션별 max — 그룹 간 중복 방지)
        Map<TopicPartition, Long> committedOffsets = collectCommittedOffsets(topic);

        store.record(topic, new OffsetSnapshot(Instant.now(), endOffsets, committedOffsets));
    }

    private Map<TopicPartition, Long> collectCommittedOffsets(String topic) throws Exception {
        List<String> groups = adminClient.listConsumerGroups()
                .all().get(TIMEOUT_SEC, TimeUnit.SECONDS)
                .stream().map(ConsumerGroupListing::groupId).toList();

        Map<TopicPartition, Long> result = new HashMap<>();
        for (String gid : groups) {
            try {
                Map<TopicPartition, OffsetAndMetadata> offsets = adminClient
                        .listConsumerGroupOffsets(gid)
                        .partitionsToOffsetAndMetadata()
                        .get(TIMEOUT_SEC, TimeUnit.SECONDS);
                for (Map.Entry<TopicPartition, OffsetAndMetadata> e : offsets.entrySet()) {
                    if (e.getKey().topic().equals(topic)) {
                        result.merge(e.getKey(), e.getValue().offset(), Math::max);
                    }
                }
            } catch (Exception ignored) {}
        }
        return result;
    }
}
