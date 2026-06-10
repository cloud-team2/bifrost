package com.bifrost.ops.internalops.operations.kafka;

public interface ConsumerGroupVerifier {
    void requireExists(String consumerGroup);
}
