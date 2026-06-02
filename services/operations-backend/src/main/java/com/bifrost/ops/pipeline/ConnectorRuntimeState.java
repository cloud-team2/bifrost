package com.bifrost.ops.pipeline;

/**
 * Connector 인스턴스 상태값(기능명세서 부록 B.2, {@code connector.state} 컬럼).
 *
 * <p>{@link #PARTIALLY_FAILED}는 일부 task만 FAILED인 Bifrost 합성 상태다(Kafka Connect
 * 원본에는 없음). watcher가 connector/task 상태를 합성해 산출한다.
 */
public enum ConnectorRuntimeState {
    RUNNING,
    PARTIALLY_FAILED,
    FAILED,
    PAUSED,
    UNASSIGNED,
    UNKNOWN
}
