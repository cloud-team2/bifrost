package com.bifrost.ops.internalops.dto;

import java.util.List;

public record ConsumerGroupsResult(
        List<ConsumerGroup> consumerGroups,
        String error
) {
    public ConsumerGroupsResult {
        consumerGroups = consumerGroups == null ? List.of() : List.copyOf(consumerGroups);
    }

    public record ConsumerGroup(
            String group,
            String state,
            Long lag,
            String owner,
            String error
    ) {}
}
