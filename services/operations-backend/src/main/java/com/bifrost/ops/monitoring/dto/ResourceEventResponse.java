package com.bifrost.ops.monitoring.dto;

import java.time.Instant;

/** KRaft rebalance·leader election 이벤트(S5). */
public record ResourceEventResponse(
        String eventType,
        String resource,
        String detail,
        Instant occurredAt
) {}
