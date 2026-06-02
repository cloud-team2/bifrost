package com.bifrost.ops.provisioning.dto;

/**
 * 프로비저닝 진행 단계. 부분 실패 시 어느 단계에서 멈췄는지 식별한다
 * (설계 §2.1: "Secret/Topic/Connector 중 어느 단계에서 실패했는지").
 *
 * <p>성공 시 {@link #COMPLETED}, 실패 시 실패가 발생한 단계를 그대로 보고한다.
 */
public enum ProvisionStage {
    /** SecretStore.resolve로 자격증명 해석 */
    SECRET,
    /** Source(Debezium) KafkaConnector CR apply */
    SOURCE_CONNECTOR,
    /** Sink(JDBC) KafkaConnector CR apply (CDC만) */
    SINK_CONNECTOR,
    /** 모든 리소스 apply 완료 */
    COMPLETED
}
