package com.bifrost.ops.pipeline;

/**
 * 파이프라인 상태값(설계 §4 3.4 {@code pipeline.status}).
 *
 * <p>단일 출처는 기능명세서 부록 B이며, connector 상태 → pipeline 상태 매핑은
 * {@code provisioning.watcher.ConnectorStateMapper}(#13)가 담당한다.
 *
 * <ul>
 *   <li>{@link #CREATING} — 생성 요청 후 RUNNING 전이 대기(최대 30초)</li>
 *   <li>{@link #ACTIVE}   — connector RUNNING</li>
 *   <li>{@link #LAG}      — 일부 task FAILED(부분 실패) 또는 lag 임계 초과</li>
 *   <li>{@link #ERROR}    — connector FAILED</li>
 *   <li>{@link #PAUSED}   — connector PAUSED</li>
 * </ul>
 */
public enum PipelineLifecycle {
    CREATING,
    ACTIVE,
    LAG,
    ERROR,
    PAUSED
}
