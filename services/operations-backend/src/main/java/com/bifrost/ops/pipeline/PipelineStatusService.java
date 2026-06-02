package com.bifrost.ops.pipeline;

/**
 * pipeline 상태 변경 단일 writer(설계 §1.2, FR-008·부록 B.2).
 *
 * <p>connector 상태 변화는 오직 이 서비스를 통해서만 pipeline row에 반영된다. watcher(#13)는
 * pipeline row를 직접 수정하지 않고 {@link #applyConnectorStatus}만 호출한다. 상태 변경 시
 * event/audit 기록과 SSE({@code pipeline_status_changed}, {@code connector_state_changed})
 * 발행은 이 서비스 구현(권세빈)이 담당한다.
 *
 * <p>이 인터페이스는 watcher가 호출할 안정 계약이다. 구현체는 권세빈의 pipeline 도메인에 있으며,
 * 미구현 상태에서는 watcher({@code provisioning.mode=real})를 활성화하지 않는다.
 */
public interface PipelineStatusService {

    /**
     * connector 상태 변경을 pipeline 상태에 반영한다(단일 writer).
     * 동일 상태 반복 통지는 멱등 처리(no-op 또는 중복 event 억제)는 구현에 위임한다.
     */
    void applyConnectorStatus(ConnectorStatusUpdate update);
}
