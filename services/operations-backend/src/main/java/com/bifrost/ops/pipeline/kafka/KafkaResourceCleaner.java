package com.bifrost.ops.pipeline.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * 파이프라인 삭제 시 Kafka 측 잔재(sink consumer group·토픽)를 정리한다(#200).
 *
 * <p>KafkaConnector CR을 지워도 Kafka Connect는 sink consumer group을 자동 삭제하지 않고,
 * Debezium이 만든 토픽도 남는다. 그대로 두면 (1) orphan consumer group의 lag이 같은 토픽을
 * 구독하는 다른 파이프라인 메트릭에 합산되고, (2) 같은 테이블로 파이프라인을 재생성하면
 * Debezium이 다시 전체 스냅샷을 떠 토픽에 이벤트가 누적된다.
 *
 * <p><b>순서가 중요하다.</b> 호출 시점엔 커넥터 CR이 막 삭제됐을 뿐 sink consumer가 아직 그룹에
 * 남아있다. 이 상태에서 (a) group을 지우면 {@code GroupNotEmptyException}이고, (b) 토픽을 지우면
 * 빠져나가는 consumer의 메타데이터 요청이 {@code auto.create.topics.enable=true} 환경에서 빈
 * 토픽을 즉시 재생성한다. 따라서 <b>consumer가 그룹에서 빠질 때까지 기다렸다 group을 먼저 지우고,
 * 토픽은 맨 마지막</b>(참조자가 사라진 뒤)에 지운다.
 *
 * <p>CR 삭제(고아 절대 미잔존, #155)와 달리 group·토픽 삭제는 <b>best-effort</b>다 — Kafka 일시
 * 장애가 파이프라인 삭제 자체를 막지 않도록, 실패해도 예외를 던지지 않고 경고만 남긴다.
 */
@Component
public class KafkaResourceCleaner {

    private static final Logger log = LoggerFactory.getLogger(KafkaResourceCleaner.class);

    /** consumer가 그룹에서 빠지길 기다리는 재시도 횟수·간격(커넥터 task 종료 후 보통 수 초). */
    private static final int GROUP_DELETE_ATTEMPTS = 6;
    private static final long GROUP_DELETE_BACKOFF_MS = 1_200L;

    private final KafkaAdmin kafkaAdmin;

    public KafkaResourceCleaner(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /**
     * 이 파이프라인의 sink consumer group({@code connect-<pid>-sink})을 비워질 때까지 기다렸다
     * 삭제하고, 그 다음 토픽을 삭제한다. 둘 다 best-effort.
     */
    public void deleteTopicAndSinkGroup(String topic, UUID pipelineId) {
        Map<String, Object> props = kafkaAdmin.getConfigurationProperties();
        try (Admin admin = Admin.create(props)) {
            // 1) consumer가 빠질 때까지 기다렸다 group 삭제 — 토픽보다 먼저(빈 group이어야 토픽 재생성 안 됨).
            deleteSinkGroup(admin, "connect-" + pipelineId + "-sink");
            // 2) 토픽 삭제 — consumer가 사라진 뒤라야 auto-create로 재생성되지 않는다.
            deleteTopic(admin, topic);
        } catch (Exception e) {
            log.warn("Kafka 리소스 정리 실패(무시): topic={}, pipeline={}, cause={}",
                    topic, pipelineId, e.getMessage());
        }
    }

    private void deleteSinkGroup(Admin admin, String group) {
        for (int attempt = 1; attempt <= GROUP_DELETE_ATTEMPTS; attempt++) {
            try {
                admin.deleteConsumerGroups(List.of(group)).all().get();
                log.info("consumer group 삭제: {}", group);
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof GroupIdNotFoundException) {
                    return; // 이미 없음 — 정상
                }
                if (cause instanceof GroupNotEmptyException && attempt < GROUP_DELETE_ATTEMPTS) {
                    sleep(GROUP_DELETE_BACKOFF_MS); // consumer가 아직 그룹에 있음 — 빠질 때까지 대기
                    continue;
                }
                log.warn("consumer group 삭제 실패(무시): group={}, cause={}", group,
                        cause != null ? cause.getMessage() : e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void deleteTopic(Admin admin, String topic) {
        if (topic == null || topic.isBlank()) return;
        try {
            admin.deleteTopics(List.of(topic)).all().get();
            log.info("토픽 삭제: {}", topic);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) return; // 이미 없음
            log.warn("토픽 삭제 실패(무시): topic={}, cause={}", topic, e.getCause() != null
                    ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
