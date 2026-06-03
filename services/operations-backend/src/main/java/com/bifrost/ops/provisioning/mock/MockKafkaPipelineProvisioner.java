package com.bifrost.ops.provisioning.mock;

import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult.ConnectorRef;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.port.KafkaPipelineProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * mock {@link KafkaPipelineProvisioner} 구현(설계 §2.1 mock-first).
 *
 * <p>실제 Kafka/K8s 리소스를 만들지 않고 naming 규칙으로 connector 이름·토픽 prefix만 계산해
 * 성공 결과를 반환한다. 프론트·상태 흐름(생성 → creating → active)을 real 구현 없이 먼저
 * 검증하기 위한 구현이다.
 *
 * <p>활성 조건: {@code provisioning.mode=mock} (미설정 시 기본값). real 구현
 * {@code StrimziKafkaPipelineProvisioner}는 {@code provisioning.mode=real}에서만 켜지므로
 * 두 빈이 동시에 뜨지 않는다(#16 스왑).
 */
@Component
@ConditionalOnProperty(name = "provisioning.mode", havingValue = "mock", matchIfMissing = true)
public class MockKafkaPipelineProvisioner implements KafkaPipelineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(MockKafkaPipelineProvisioner.class);

    public MockKafkaPipelineProvisioner() {
        log.info("MockKafkaPipelineProvisioner 활성화 — 실제 CR을 만들지 않고 이름/상태만 반환합니다(mock-first).");
    }

    @Override
    public PipelineProvisionResult createPipelineResources(PipelineProvisionCommand command) {
        String topicPrefix = ConnectorNaming.topicPrefix(
                command.projectKey(), command.source().dbName());

        List<ConnectorRef> connectors = new ArrayList<>();
        connectors.add(new ConnectorRef(
                ConnectorNaming.sourceConnectorName(command.pipelineId()),
                ConnectorKind.SOURCE,
                "mock.SourceConnector"));
        if (command.pattern() == PipelinePattern.DIRECT) {
            connectors.add(new ConnectorRef(
                    ConnectorNaming.sinkConnectorName(command.pipelineId()),
                    ConnectorKind.SINK,
                    "mock.SinkConnector"));
        }

        log.info("[mock] 파이프라인 리소스 생성: pipeline={}, pattern={}, connectors={}",
                command.pipelineId(), command.pattern(), connectors.size());
        return PipelineProvisionResult.success(command.pipelineId(), connectors, topicPrefix);
    }

    @Override
    public PipelineProvisionStatus getConnectorStatus(String projectId, String connectorName) {
        // mock은 항상 RUNNING으로 응답해 creating→active 전이를 흉내낸다.
        return new PipelineProvisionStatus(connectorName, "RUNNING", List.of(
                new PipelineProvisionStatus.TaskState(0, "RUNNING", null)));
    }

    @Override
    public void deletePipelineResources(PipelineResourceRef resourceRef) {
        log.info("[mock] 파이프라인 리소스 삭제: pipeline={}, connectors={}",
                resourceRef.pipelineId(), resourceRef.connectorNames());
    }
}
