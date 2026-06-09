package com.bifrost.ops.internalops.dto;

public record ConsumerLagResult(String consumerGroup, long totalLag, String source) {}
