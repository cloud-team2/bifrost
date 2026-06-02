package com.bifrost.ops.pipeline;

/**
 * watcher → {@link PipelineStatusService} 상태 변경 전달 페이로드(#13).
 *
 * <p>watcher가 KafkaConnector CR의 connector/task 상태를 합성({@link ConnectorRuntimeState})하고
 * pipeline 상태 후보({@link PipelineLifecycle})까지 매핑해 전달한다. pipeline row 갱신·event·SSE는
 * {@link PipelineStatusService}(단일 writer)가 수행한다.
 *
 * @param connectorName  KafkaConnector CR 이름({@code {pipelineId}-source|-sink})
 * @param connectorState 합성 connector 상태
 * @param pipelineStatus 매핑된 pipeline 상태 후보
 * @param totalTasks     전체 task 수
 * @param failedTasks    FAILED task 수
 * @param lastError      마지막 오류 요약(비밀값 미포함, 길이 제한). 없으면 null
 */
public record ConnectorStatusUpdate(
        String connectorName,
        ConnectorRuntimeState connectorState,
        PipelineLifecycle pipelineStatus,
        int totalTasks,
        int failedTasks,
        String lastError
) {
    public ConnectorStatusUpdate {
        if (connectorName == null || connectorName.isBlank()) {
            throw new IllegalArgumentException("connectorName must not be blank");
        }
        if (connectorState == null) {
            throw new IllegalArgumentException("connectorState must not be null");
        }
        if (pipelineStatus == null) {
            throw new IllegalArgumentException("pipelineStatus must not be null");
        }
    }
}
