package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.pipeline.dto.KafkaMessageRecord;
import com.bifrost.ops.pipeline.dto.MessagePageResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 파이프라인 토픽 메시지 조회(#126). KafkaConsumer manual assignment로 각 파티션 tail에서 읽는다.
 * Debezium envelope(before/after/op)을 파싱하며, 비 Debezium 메시지는 after에 raw 값을 담는다.
 * Kafka 접근 불가 시 빈 목록을 반환한다.
 */
@Service
public class PipelineMessageService {

    private static final Logger log = LoggerFactory.getLogger(PipelineMessageService.class);

    private final PipelineRepository pipelineRepository;
    private final WorkspaceAccessGuard accessGuard;
    private final KafkaAdmin kafkaAdmin;
    private final ObjectMapper objectMapper;

    public PipelineMessageService(PipelineRepository pipelineRepository,
                                  WorkspaceAccessGuard accessGuard,
                                  KafkaAdmin kafkaAdmin,
                                  ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.accessGuard = accessGuard;
        this.kafkaAdmin = kafkaAdmin;
        this.objectMapper = objectMapper;
    }

    public List<KafkaMessageRecord> messages(UUID wsId, AuthenticatedUser principal,
                                             UUID id, int limit) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = pipelineRepository.findByIdAndTenantId(id, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND,
                        "파이프라인을 찾을 수 없습니다"));

        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) return List.of();

        try {
            return fetchMessages(topic, Math.min(limit, 100));
        } catch (Exception e) {
            log.warn("메시지 조회 실패 (Kafka 접근 불가): topic={}, cause={}", topic, e.getMessage());
            return List.of();
        }
    }

    /**
     * 단일 파티션의 오프셋 윈도우를 결정적으로 읽는다(#509, 페이징). {@code startOffset}이 null이면
     * 해당 파티션 최신 {@code limit}개. 과거 메시지까지 페이지 단위로 열람 가능.
     */
    public MessagePageResponse messagePage(UUID wsId, AuthenticatedUser principal, UUID id,
                                           int partition, Long startOffset, int limit) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = pipelineRepository.findByIdAndTenantId(id, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND,
                        "파이프라인을 찾을 수 없습니다"));

        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) return MessagePageResponse.empty(partition);

        int size = Math.max(1, Math.min(limit, 200));
        try {
            return fetchPage(topic, partition, startOffset, size);
        } catch (Exception e) {
            log.warn("메시지 페이지 조회 실패 (Kafka 접근 불가): topic={}, p={}, cause={}",
                    topic, partition, e.getMessage());
            return MessagePageResponse.empty(partition);
        }
    }

    private MessagePageResponse fetchPage(String topic, int partition, Long startOffset, int limit) {
        Map<String, Object> props = consumerProps(limit);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partInfos = consumer.partitionsFor(topic);
            if (partInfos == null || partInfos.stream().noneMatch(pi -> pi.partition() == partition)) {
                return MessagePageResponse.empty(partition);
            }
            TopicPartition tp = new TopicPartition(topic, partition);
            List<TopicPartition> one = List.of(tp);
            consumer.assign(one);

            long begin = consumer.beginningOffsets(one).getOrDefault(tp, 0L);
            long end = consumer.endOffsets(one).getOrDefault(tp, 0L);

            // startOffset 미지정 → 최신 N(end-limit). 지정 시 [begin, end] 범위로 클램프.
            long start = (startOffset == null)
                    ? Math.max(begin, end - limit)
                    : Math.min(Math.max(startOffset, begin), Math.max(begin, end));
            long target = Math.min(end, start + limit);  // 이 페이지가 포함할 마지막+1 offset

            consumer.seek(tp, start);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 5_000;
            while (start < target && consumer.position(tp) < target
                    && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                if (polled.isEmpty()) {
                    if (consumer.position(tp) >= target) break;
                    continue;
                }
                for (ConsumerRecord<String, String> r : polled.records(tp)) {
                    if (r.offset() >= target) break;
                    records.add(r);
                }
            }

            // 페이지 내 최신순(offset desc) — 기존 테이블 표시와 동일.
            List<KafkaMessageRecord> out = records.stream()
                    .sorted(Comparator.comparingLong(ConsumerRecord<String, String>::offset).reversed())
                    .limit(limit)
                    .map(this::toRecord)
                    .toList();

            boolean hasOlder = start > begin;
            boolean hasNewer = target < end;
            return new MessagePageResponse(out, partition, start, begin, end, hasOlder, hasNewer);
        }
    }

    private Map<String, Object> consumerProps(int maxPoll) {
        Map<String, Object> props = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPoll);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
        return props;
    }

    private List<KafkaMessageRecord> fetchMessages(String topic, int limit) {
        Map<String, Object> props = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, limit);
        // 로컬: K8s advertised address resolve 실패 시 빠르게 실패하도록 타임아웃 단축
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partInfos = consumer.partitionsFor(topic);
            if (partInfos == null || partInfos.isEmpty()) return List.of();

            List<TopicPartition> tps = partInfos.stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
            consumer.assign(tps);

            // end offsets
            Map<TopicPartition, Long> ends = consumer.endOffsets(tps);

            // seek to (end - perPartition) per partition
            int perPartition = Math.max(1, limit / tps.size() + 1);
            for (TopicPartition tp : tps) {
                long end = ends.getOrDefault(tp, 0L);
                consumer.seek(tp, Math.max(0, end - perPartition));
            }

            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(5));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            polled.forEach(records::add);

            // 최신순 = offset 내림차순. timestamp로 정렬하면 Debezium 스냅샷처럼 timestamp가
            // 동일·근접한 이벤트들의 offset 순서가 뒤섞이므로 offset을 기준으로 한다(파티션 내 단조 증가).
            return records.stream()
                    .sorted(Comparator.comparingInt(ConsumerRecord<String, String>::partition)
                            .thenComparingLong(ConsumerRecord::offset).reversed())
                    .limit(limit)
                    .map(this::toRecord)
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private KafkaMessageRecord toRecord(ConsumerRecord<String, String> r) {
        String op = null;
        Object before = null;
        Object after = null;

        if (r.value() != null) {
            try {
                Map<String, Object> msg = objectMapper.readValue(r.value(), Map.class);
                // Debezium with schema wrapper: {schema:{...}, payload:{op, before, after}}
                Map<String, Object> payload = msg.containsKey("payload")
                        ? (Map<String, Object>) msg.get("payload")
                        : msg;
                op = (String) payload.get("op");
                before = payload.get("before");
                after = payload.get("after");
                if (before == null && after == null && op == null) {
                    after = payload; // non-Debezium: treat whole value as after
                }
            } catch (Exception e) {
                after = r.value(); // raw string fallback
            }
        }

        return new KafkaMessageRecord(
                r.partition(), r.offset(), r.timestamp(),
                r.key(), op, before, after);
    }
}
