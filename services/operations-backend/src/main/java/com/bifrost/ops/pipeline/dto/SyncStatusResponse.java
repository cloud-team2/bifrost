package com.bifrost.ops.pipeline.dto;

import java.time.Instant;

/**
 * 파이프라인 동기화 상태 응답(#107, 상세 Sync 탭). source/sink 테이블의 실제 행수를 비교한다.
 *
 * <p>{@code sourceRows}/{@code sinkRows}는 조회 시점에 각 DB에 {@code SELECT COUNT(*)}를 실행한
 * 실값이며, 접속 실패·테이블 미존재(생성 중) 시 {@code -1}이다. {@code delta}는 source-sink
 * 차이(미반영 추정 행수)로, 한쪽이라도 -1이면 -1이다.
 */
public record SyncStatusResponse(
    long sourceRows,
    long sinkRows,
    long delta,
    Instant checkedAt
) {}
