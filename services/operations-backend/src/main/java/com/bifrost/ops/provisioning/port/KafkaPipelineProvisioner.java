package com.bifrost.ops.provisioning.port;

import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;

/**
 * 파이프라인 Kafka 리소스 생성·조회·삭제 추상화(설계 §2.1).
 *
 * <p>파이프라인 생성 흐름을 실제 Kafka/K8s 구현과 인터페이스로 분리한다. 구현체는
 * {@code provisioning.impl.strimzi.StrimziKafkaPipelineProvisioner} — Fabric8로 Strimzi
 * KafkaConnector CR을 apply/watch한다. command/result 계약이 고정되어 있어 호출부는 구현을 모른다.
 */
public interface KafkaPipelineProvisioner {

    /**
     * 파이프라인 리소스를 생성한다. EDA면 Source 1개, CDC면 Source+Sink를 apply한다.
     * 부분 실패는 예외가 아니라 {@link PipelineProvisionResult}의 {@code stage}/{@code success}로 구분한다.
     */
    PipelineProvisionResult createPipelineResources(PipelineProvisionCommand command);

    /** connector 런타임 상태를 조회한다(read-only). */
    PipelineProvisionStatus getConnectorStatus(String projectId, String connectorName);

    /** 파이프라인이 점유한 connector CR을 삭제한다(생명주기 delete). */
    void deletePipelineResources(PipelineResourceRef resourceRef);
}
