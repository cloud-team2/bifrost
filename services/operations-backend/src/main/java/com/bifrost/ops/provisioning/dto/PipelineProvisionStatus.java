package com.bifrost.ops.provisioning.dto;

import java.util.List;

/**
 * KafkaConnector 런타임 상태 조회 결과(설계 §2.1 {@code getConnectorStatus}).
 *
 * <p>{@code .status.connectorStatus.connector.state}와 task별 상태를 그대로 담는다.
 * connector 상태 → pipeline 상태 매핑(RUNNING→active 등)은 #13 watcher 영역의
 * {@code ConnectorStateMapper}가 단일 출처로 담당한다.
 *
 * @param connectorName  KafkaConnector CR 이름
 * @param connectorState connector 레벨 상태(RUNNING/FAILED/PAUSED/UNASSIGNED/UNKNOWN)
 * @param tasks          task별 상태
 */
public record PipelineProvisionStatus(
        String connectorName,
        String connectorState,
        List<TaskState> tasks
) {

    public PipelineProvisionStatus {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** task 단위 상태. */
    public record TaskState(int id, String state, String trace) {}
}
