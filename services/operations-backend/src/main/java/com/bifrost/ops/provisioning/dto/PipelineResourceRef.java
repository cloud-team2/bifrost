package com.bifrost.ops.provisioning.dto;

import java.util.List;
import java.util.UUID;

/**
 * 파이프라인이 점유한 Kafka 리소스 참조(설계 §2.1 {@code deletePipelineResources} 입력,
 * 생명주기 §5 delete).
 *
 * @param pipelineId     대상 파이프라인
 * @param namespace      리소스 네임스페이스(예: platform-kafka)
 * @param connectorNames 삭제 대상 KafkaConnector CR 이름(EDA 1개 / CDC 2개)
 */
public record PipelineResourceRef(
        UUID pipelineId,
        String namespace,
        List<String> connectorNames
) {

    public PipelineResourceRef {
        if (pipelineId == null) {
            throw new IllegalArgumentException("pipelineId must not be null");
        }
        connectorNames = connectorNames == null ? List.of() : List.copyOf(connectorNames);
    }
}
