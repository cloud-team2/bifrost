package com.bifrost.ops.internalops.operations.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaConsumerGroupVerifier implements ConsumerGroupVerifier {

    private static final long TIMEOUT_SEC = 5L;

    private final AdminClient adminClient;

    public KafkaConsumerGroupVerifier(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    @Override
    public void requireExists(String consumerGroup) {
        try {
            Map<String, ConsumerGroupDescription> descriptions = adminClient
                    .describeConsumerGroups(List.of(consumerGroup))
                    .all()
                    .get(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!descriptions.containsKey(consumerGroup)) {
                throw ConsumerGroupVerificationException.notFound(consumerGroup);
            }
        } catch (ConsumerGroupVerificationException e) {
            throw e;
        } catch (ExecutionException e) {
            // 미존재 group은 not-found(404), 그 외 broker 오류/타임아웃은 unavailable(502)
            if (e.getCause() instanceof GroupIdNotFoundException) {
                throw ConsumerGroupVerificationException.notFound(consumerGroup);
            }
            throw ConsumerGroupVerificationException.unavailable(consumerGroup, e);
        } catch (Exception e) {
            throw ConsumerGroupVerificationException.unavailable(consumerGroup, e);
        }
    }
}
