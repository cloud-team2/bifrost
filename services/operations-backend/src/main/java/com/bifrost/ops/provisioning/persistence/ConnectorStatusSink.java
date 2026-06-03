package com.bifrost.ops.provisioning.persistence;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;

/**
 * watcher가 감지한 connector 런타임 상태를 {@code connectors} 메타데이터 테이블에 반영하는 sink(#46).
 *
 * <p>{@link com.bifrost.ops.pipeline.PipelineStatusService}(권세빈, pipeline row 단일 writer)와는
 * 책임이 다르다. 이 sink는 connector 단위 운영 메타(state/last_error/updated_at)만 갱신하고,
 * pipeline 상태/이벤트/SSE에는 관여하지 않는다. watcher는 두 경로(connector 메타 sink,
 * pipeline status service)를 모두 호출한다.
 */
public interface ConnectorStatusSink {

    /** connector CR 상태를 해당 connectors 행에 반영한다. 행이 없으면 no-op(경고만). */
    void record(ConnectorStatusUpdate update);
}
