package com.bifrost.ops.provisioning.dto;

/** KafkaConnector 종류. {@code connector.kind} 컬럼(설계 §4 3.5)과 동일. */
public enum ConnectorKind {
    SOURCE,
    SINK
}
