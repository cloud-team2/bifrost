package com.bifrost.ops.pipeline.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 파이프라인 삭제 시 Kafka 측 잔재(토픽·sink consumer group)를 정리한다(#200).
 *
 * <p>KafkaConnector CR을 지워도 Kafka Connect는 sink consumer group을 자동 삭제하지 않고,
 * Debezium이 만든 토픽도 남는다. 그대로 두면 (1) orphan consumer group의 lag이 같은 토픽을
 * 구독하는 다른 파이프라인 메트릭에 합산되고, (2) 같은 테이블로 파이프라인을 재생성하면
 * Debezium이 다시 전체 스냅샷을 떠 토픽에 이벤트가 누적된다.
 *
 * <p>CR 삭제(고아 절대 미잔존, #155)와 달리 토픽·group 삭제는 <b>best-effort</b>다 — Kafka 일시
 * 장애가 파이프라인 삭제 자체를 막지 않도록, 실패해도 예외를 던지지 않고 경고만 남긴다.
 */
@Component
public class KafkaResourceCleaner {

    private static final Logger log = LoggerFactory.getLogger(KafkaResourceCleaner.class);

    private final KafkaAdmin kafkaAdmin;

    public KafkaResourceCleaner(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /**
     * 토픽과 이 파이프라인의 sink consumer group({@code connect-<pid>-sink})을 삭제한다.
     * 둘 다 best-effort — 없는 리소스는 무시, 접근 실패는 경고 로그 후 통과.
     */
    public void deleteTopicAndSinkGroup(String topic, UUID pipelineId) {
        Map<String, Object> props = kafkaAdmin.getConfigurationProperties();
        try (Admin admin = Admin.create(props)) {
            if (topic != null && !topic.isBlank()) {
                try {
                    admin.deleteTopics(List.of(topic)).all().get();
                    log.info("토픽 삭제: {}", topic);
                } catch (Exception e) {
                    log.warn("토픽 삭제 실패(무시): topic={}, cause={}", topic, e.getMessage());
                }
            }
            // Kafka Connect sink 커넥터의 consumer group = connect-<커넥터명>, 커넥터명 = <pid>-sink.
            String group = "connect-" + pipelineId + "-sink";
            try {
                admin.deleteConsumerGroups(List.of(group)).all().get();
                log.info("consumer group 삭제: {}", group);
            } catch (Exception e) {
                log.warn("consumer group 삭제 실패(무시): group={}, cause={}", group, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Kafka 리소스 정리 실패(무시): topic={}, pipeline={}, cause={}",
                    topic, pipelineId, e.getMessage());
        }
    }
}
