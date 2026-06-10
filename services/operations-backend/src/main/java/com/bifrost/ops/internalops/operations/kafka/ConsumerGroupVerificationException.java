package com.bifrost.ops.internalops.operations.kafka;

public class ConsumerGroupVerificationException extends RuntimeException {
    private final boolean unavailable;

    private ConsumerGroupVerificationException(String message, boolean unavailable, Throwable cause) {
        super(message, cause);
        this.unavailable = unavailable;
    }

    public static ConsumerGroupVerificationException notFound(String groupId) {
        return new ConsumerGroupVerificationException("consumer group not found: " + groupId, false, null);
    }

    public static ConsumerGroupVerificationException unavailable(String groupId, Throwable cause) {
        return new ConsumerGroupVerificationException("consumer group verification unavailable: " + groupId, true, cause);
    }

    public boolean isUnavailable() {
        return unavailable;
    }
}
