package com.bifrost.ops.pipeline.status;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.provisioning.persistence.ConnectorStatusSink;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * mock 모드 전용: real 모드의 {@code KafkaConnectorWatcher} 역할을 대신해 파이프라인의 모든
 * connector를 RUNNING으로 보고한다(#70). real 모드에서는 이 빈이 없고 실제 watcher가 동작한다.
 *
 * <p>watcher와 동일하게 두 경로를 호출한다:
 * {@link ConnectorStatusSink#record}(connectors 메타 행 갱신) →
 * {@link PipelineStatusService#applyConnectorStatus}(단일 writer가 pipeline 상태 재계산).
 * 따라서 mock의 {@code creating → active} 전이도 PipelineStatusService를 통과한다.
 */
@Component
@ConditionalOnProperty(name = "provisioning.mode", havingValue = "mock", matchIfMissing = true)
public class PipelineActivationSimulator {

    private final ConnectorRepository connectorRepository;
    private final ConnectorStatusSink statusSink;
    private final PipelineStatusService statusService;

    public PipelineActivationSimulator(ConnectorRepository connectorRepository,
                                       ConnectorStatusSink statusSink,
                                       PipelineStatusService statusService) {
        this.connectorRepository = connectorRepository;
        this.statusSink = statusSink;
        this.statusService = statusService;
    }

    /** 파이프라인의 모든 connector를 RUNNING으로 통지한다. */
    public void simulateRunning(UUID pipelineId) {
        List<ConnectorEntity> connectors = connectorRepository.findByPipelineId(pipelineId);
        for (ConnectorEntity c : connectors) {
            ConnectorStatusUpdate update = new ConnectorStatusUpdate(
                    c.getCrName(), ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE,
                    c.getTasksMax(), 0, null);
            statusSink.record(update);
            statusService.applyConnectorStatus(update);
        }
    }
}
