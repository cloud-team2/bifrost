package com.bifrost.ops.provisioning.dto;

/**
 * 파이프라인 패턴(설계 §2 Provisioning 4).
 *
 * <ul>
 *   <li>{@link #FAN_OUT} — EDA. Source Debezium connector 1개만 생성(Sink 없음).</li>
 *   <li>{@link #DIRECT}  — CDC. Source Debezium + Sink JDBC connector 생성.</li>
 * </ul>
 */
public enum PipelinePattern {
    FAN_OUT,
    DIRECT
}
