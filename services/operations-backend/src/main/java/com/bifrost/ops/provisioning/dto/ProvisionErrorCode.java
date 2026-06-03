package com.bifrost.ops.provisioning.dto;

/**
 * 파이프라인 프로비저닝 실패 원인 코드(#15).
 *
 * <p>API 공통 {@code com.bifrost.ops.api.error.ErrorCode}는 HTTP 응답 단위의 코드인 반면,
 * 이 enum은 {@link PipelineProvisionResult} 안에서 실패 {@link ProvisionStage}와 함께
 * 전달/저장되는 도메인 상세 코드다. 호출부(pipeline 서비스)는 이 코드로 pipeline 상태를
 * {@code error}로 반영하고 원인을 구분한다(설계 §2.1 부분 실패 구분).
 *
 * <p>각 코드는 실패가 발생한 {@link ProvisionStage}와 1:1로 매핑된다.
 */
public enum ProvisionErrorCode {

    /** SecretStore.resolve 단계 실패(자격증명 없음/해석 불가). {@link ProvisionStage#SECRET}. */
    SECRET_RESOLVE_FAILED(ProvisionStage.SECRET),

    /** Source(Debezium) KafkaConnector CR apply 실패. {@link ProvisionStage#SOURCE_CONNECTOR}. */
    SOURCE_CONNECTOR_FAILED(ProvisionStage.SOURCE_CONNECTOR),

    /** Sink(JDBC) KafkaConnector CR apply 실패(CDC만). {@link ProvisionStage#SINK_CONNECTOR}. */
    SINK_CONNECTOR_FAILED(ProvisionStage.SINK_CONNECTOR);

    private final ProvisionStage stage;

    ProvisionErrorCode(ProvisionStage stage) {
        this.stage = stage;
    }

    /** 이 에러 코드가 발생하는 단계. */
    public ProvisionStage stage() {
        return stage;
    }

    /** 직렬화/저장용 문자열 코드. */
    public String code() {
        return name();
    }
}
