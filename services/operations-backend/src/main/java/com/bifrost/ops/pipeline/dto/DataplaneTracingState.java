package com.bifrost.ops.pipeline.dto;

/** 데이터플레인 추적 토글 현재 상태(#565, Tracing 탭). source 커넥터의 tracing SMT 유무. */
public record DataplaneTracingState(boolean enabled) {}
