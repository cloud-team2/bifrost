package com.bifrost.ops.internalops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kafka Connect가 소유한 consumer group mutation 결과. */
public record ConsumerGroupActionResult(
        @JsonProperty("consumer_group") String consumerGroup,
        String action,
        String status,
        String message
) {
    public static ConsumerGroupActionResult accepted(String consumerGroup, String action) {
        return new ConsumerGroupActionResult(consumerGroup, action, "SUCCESS", "accepted");
    }

    public static ConsumerGroupActionResult replay(String consumerGroup, String action) {
        return new ConsumerGroupActionResult(consumerGroup, action, "IDEMPOTENCY_REPLAY",
                "idempotent duplicate; mutation not re-executed");
    }
}
