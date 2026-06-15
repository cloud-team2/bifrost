package com.bifrost.ops.provisioning.dto;

import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 리소스 생성 결과(provisioner 출력).
 *
 * <p>설계 §2.1: 부분 실패를 result로 구분한다 — {@code success=false}면 {@link #stage}가
 * 실패가 발생한 단계({@code SECRET}/{@code SOURCE_CONNECTOR}/{@code SINK_CONNECTOR})를 가리키고
 * {@code errorCode}/{@code message}로 원인을 전달한다. 호출부(pipeline 서비스)는 이를 보고
 * pipeline 상태를 {@code error}로 반영한다. 성공 시 {@code stage=COMPLETED}.
 *
 * @param pipelineId 대상 파이프라인
 * @param success    전체 성공 여부
 * @param stage      성공 시 {@code COMPLETED}, 실패 시 실패 단계
 * @param connectors 생성(apply)된 connector 참조 목록(성공한 것까지)
 * @param topicPrefix 토픽 base prefix(프로젝트·datasource 범위 추적용). Source Debezium {@code topic.prefix}는
 *                    최종 토픽명({@code {base}.{schema}.{table}})을 사용한다(#365).
 * @param errorCode  실패 시 에러 코드(없으면 null)
 * @param message    사람이 읽는 요약(성공/실패 공통, 비밀값 미포함)
 */
public record PipelineProvisionResult(
        UUID pipelineId,
        boolean success,
        ProvisionStage stage,
        List<ConnectorRef> connectors,
        String topicPrefix,
        String errorCode,
        String message
) {

    public PipelineProvisionResult {
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
    }

    /** apply된 connector 참조. */
    public record ConnectorRef(String name, ConnectorKind kind, String connectorClass) {}

    public static PipelineProvisionResult success(UUID pipelineId,
                                                  List<ConnectorRef> connectors,
                                                  String topicPrefix) {
        return new PipelineProvisionResult(
                pipelineId, true, ProvisionStage.COMPLETED, connectors, topicPrefix, null,
                "provisioned " + connectors.size() + " connector(s)");
    }

    /**
     * 단계별 실패 결과. {@link ProvisionErrorCode}가 stage와 code를 함께 고정하므로
     * 호출부는 stage/errorCode 불일치를 걱정하지 않아도 된다(#15).
     */
    public static PipelineProvisionResult failure(UUID pipelineId,
                                                  ProvisionErrorCode errorCode,
                                                  List<ConnectorRef> created,
                                                  String topicPrefix,
                                                  String message) {
        return new PipelineProvisionResult(
                pipelineId, false, errorCode.stage(), created, topicPrefix, errorCode.code(), message);
    }

    public static PipelineProvisionResult failure(UUID pipelineId,
                                                  ProvisionStage failedStage,
                                                  List<ConnectorRef> created,
                                                  String topicPrefix,
                                                  String errorCode,
                                                  String message) {
        return new PipelineProvisionResult(
                pipelineId, false, failedStage, created, topicPrefix, errorCode, message);
    }
}
