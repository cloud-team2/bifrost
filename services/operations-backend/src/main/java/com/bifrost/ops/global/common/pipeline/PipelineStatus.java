package com.bifrost.ops.global.common.pipeline;

/**
 * Pipeline 상태.
 * orchestrator의 KafkaConnector status를 우리 도메인 용어로 매핑.
 */
public enum PipelineStatus {
    /** 생성 요청됨, K8s 적용 대기 또는 진행 중 */
    PENDING,
    /** Connector 정상 동작 */
    RUNNING,
    /** Connector는 살아있지만 일부 Task 실패 */
    DEGRADED,
    /** Connector 시작 실패 또는 완전 실패 */
    FAILED,
    /** 삭제 진행 중 */
    DELETING
}
