package com.bifrost.ops.pipeline;

/**
 * pipeline 상태 변경 단일 writer(설계 §1.2, FR-008·부록 B.2).
 *
 * <p>connector 상태 변화는 오직 이 서비스를 통해서만 pipeline row에 반영된다. watcher(#13)는
 * pipeline row를 직접 수정하지 않고 {@link #applyConnectorStatus}만 호출한다. 상태 변경 시
 * event/audit 기록과 SSE({@code pipeline_status_changed}, {@code connector_state_changed})
 * 발행은 이 서비스 구현(권세빈)이 담당한다.
 *
 * <p>이 인터페이스는 watcher가 호출할 안정 계약이다. 구현체는 {@code PipelineStatusServiceImpl}이다.
 */
public interface PipelineStatusService {

    /**
     * connector 상태 변경을 pipeline 상태에 반영한다(단일 writer).
     * 동일 상태 반복 통지는 멱등 처리(no-op 또는 중복 event 억제)는 구현에 위임한다.
     */
    void applyConnectorStatus(ConnectorStatusUpdate update);

    /**
     * {@code timeout}보다 오래 {@code creating}에 머문 파이프라인을 {@code error}로 전이한다(#155 백스톱).
     * NotReady condition조차 안 뜨고 멈춘 경우까지 'creating은 막다른 상태가 아니다'를 보장한다.
     *
     * @return error로 전이된 파이프라인 수
     */
    int failTimedOutCreating(java.time.Duration timeout);

    /**
     * 특정 datasource(source/sink) 연결 헬스가 바뀌었을 때, 이를 쓰는 파이프라인 상태를 재평가한다(#179).
     * source DB가 죽어도 커넥터가 retry로 RUNNING을 유지(이벤트 미발생)하는 경우까지 반영한다.
     */
    void reevaluateForDatasource(java.util.UUID datasourceId);

    /**
     * consumer lag을 pipeline 상태에 반영한다(#559, 스펙 B.1). KafkaAdminPoller가 주기적으로 호출한다.
     * 커넥터 RUNNING + lag ≥ 경고 임계(기본 5,000)면 {@code lag}, 회복되면 {@code active}로 전이한다.
     * error/paused/creating는 우선순위가 높아 lag로 덮어쓰지 않는다.
     */
    void applyConsumerLag(java.util.UUID pipelineId, long lag);
}
