package com.bifrost.ops.provisioning;

import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.port.KafkaPipelineProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 파이프라인 생성/조회/삭제 호출 경로의 단일 진입점(#45).
 *
 * <p>API 계층({@code InternalController})과 provisioner 구현 사이의 seam이다. 컨트롤러는
 * 구체 구현을 모르고 {@link KafkaPipelineProvisioner} 포트만 의존한다
 * (구현체는 {@code StrimziKafkaPipelineProvisioner}).
 *
 * <p>connector 메타데이터 영속화(connectors 테이블 반영)는 watcher 상태 adapter(#46)가 맡는다.
 * 여기서는 호출 위임과 로깅 경계만 둔다.
 */
@Service
public class PipelineProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(PipelineProvisioningService.class);

    private final KafkaPipelineProvisioner provisioner;

    public PipelineProvisioningService(KafkaPipelineProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /** EDA면 Source 1개, CDC면 Source+Sink를 생성한다. 부분 실패는 result.success/stage로 구분. */
    public PipelineProvisionResult provision(PipelineProvisionCommand command) {
        log.info("pipeline 생성 요청: pipeline={}, pattern={}", command.pipelineId(), command.pattern());
        PipelineProvisionResult result = provisioner.createPipelineResources(command);
        if (result.success()) {
            log.info("pipeline 생성 수락: pipeline={}, connectors={}, topicPrefix={}",
                    result.pipelineId(), result.connectors().size(), result.topicPrefix());
        } else {
            log.warn("pipeline 생성 실패: pipeline={}, stage={}, code={}",
                    result.pipelineId(), result.stage(), result.errorCode());
        }
        return result;
    }

    /** connector 런타임 상태 조회(read-only). */
    public PipelineProvisionStatus status(String projectId, String connectorName) {
        return provisioner.getConnectorStatus(projectId, connectorName);
    }

    /** 파이프라인이 점유한 connector CR 삭제. */
    public void delete(PipelineResourceRef resourceRef) {
        log.info("pipeline 삭제 요청: pipeline={}, connectors={}",
                resourceRef.pipelineId(), resourceRef.connectorNames());
        provisioner.deletePipelineResources(resourceRef);
    }
}
