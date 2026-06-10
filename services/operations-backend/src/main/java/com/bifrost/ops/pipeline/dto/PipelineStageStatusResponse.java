package com.bifrost.ops.pipeline.dto;

import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 단계별(source/sink) 상태 귀속(#367, 상시 A RCA).
 *
 * <p>"어느 단계가 느린지/실패인지"를 한 번에 노출한다. 단계 {@code status}는 커넥터 watcher state에서
 * 도출하고, source {@code delayMs}·sink {@code lagMessages}는 best-effort로 첨부한다
 * (Prometheus/Kafka 미가용 시 null).
 *
 * @param pipelineId 파이프라인 id
 * @param overall    파이프라인 종합 상태(creating/active/lag/error/paused)
 * @param stages     단계별 상태(EDA는 SOURCE만, CDC는 SOURCE+SINK)
 * @param bottleneck 첫 FAILED 단계, 없으면 첫 DEGRADED 단계명. 정상이면 null
 */
public record PipelineStageStatusResponse(
        UUID pipelineId,
        String overall,
        List<StageStatus> stages,
        String bottleneck
) {
    /**
     * @param stage          SOURCE | SINK
     * @param connectorState 커넥터 watcher state(RUNNING/FAILED/PARTIALLY_FAILED/PAUSED/UNKNOWN)
     * @param status         OK | DEGRADED | FAILED | PAUSED | UNKNOWN (connectorState 도출)
     * @param error          커넥터 lastError(있으면)
     * @param delayMs        SOURCE 소스 지연(ms), 미가용 시 null
     * @param lagMessages    SINK consumer lag(메시지 수), 미가용 시 null
     */
    public record StageStatus(
            String stage,
            String connectorState,
            String status,
            String error,
            Long delayMs,
            Long lagMessages
    ) {}
}
