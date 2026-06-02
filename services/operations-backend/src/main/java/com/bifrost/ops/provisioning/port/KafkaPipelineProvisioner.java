package com.bifrost.ops.provisioning.port;

import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;

/**
 * 파이프라인 Kafka 리소스 생성·조회·삭제 추상화(설계 §2.1).
 *
 * <p>파이프라인 생성 흐름을 실제 Kafka/K8s 구현과 인터페이스로 분리한다. 같은 계약 위에서
 * 두 구현이 공존한다:
 * <ul>
 *   <li><b>mock</b>({@code provisioning.mock}, 권세빈) — CR을 만들지 않고 이름·상태만 반환,
 *       프론트·상태 흐름을 먼저 검증.</li>
 *   <li><b>real</b>({@code provisioning.impl.strimzi}, 백강민) — Fabric8로 Strimzi
 *       KafkaConnector CR을 apply/watch.</li>
 * </ul>
 *
 * <p>구현 스왑은 설정으로 제어한다(예: {@code provisioning.mode=mock|real}, #16/목요일).
 * command/result 계약이 고정되어 있으므로 두 작업을 병렬로 진행할 수 있다.
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
