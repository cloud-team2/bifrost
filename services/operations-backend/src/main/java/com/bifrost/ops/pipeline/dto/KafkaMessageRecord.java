package com.bifrost.ops.pipeline.dto;

/**
 * Kafka 메시지 레코드(#126). Debezium envelope에서 파싱한 before/after 포함.
 *
 * @param sizeBytes serialized key/value byte size from Kafka. Kafka가 크기를 제공하지 않으면 null.
 * @param op  Debezium 이벤트 타입: c(insert)/u(update)/d(delete)/r(read). 비 Debezium이면 null.
 */
public record KafkaMessageRecord(
        int partition,
        long offset,
        long tsMs,
        String key,
        Long sizeBytes,
        String op,
        Object before,
        Object after
) {}
