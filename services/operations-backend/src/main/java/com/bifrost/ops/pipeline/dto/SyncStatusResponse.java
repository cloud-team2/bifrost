package com.bifrost.ops.pipeline.dto;

import java.time.Instant;

/**
 * 파이프라인 동기화 상태 응답(#107, 상세 Sync 탭). source/sink 테이블의 실제 행수를 비교한다.
 *
 * <p>{@code sourceRows}/{@code sinkRows}는 조회 시점에 각 DB에 {@code SELECT COUNT(*)}를 실행한
 * 실값이며, 접속 실패·테이블 미존재(생성 중) 시 {@code -1}이다. {@code delta}는 source-sink
 * 차이(미반영 추정 행수)로, 한쪽이라도 -1이면 -1이다.
 *
 * <p>EDA(fan-out) 파이프라인은 sink가 없어 동기화 개념이 없으므로 {@code applicable=false}로 반환된다.
 * 이때 나머지 필드는 모두 {@code -1}이다.
 */
public record SyncStatusResponse(
    long sourceRows,
    long sinkRows,
    long delta,
    Instant checkedAt,
    boolean applicable,
    // #501: 동기화 완료 판정을 행수가 아니라 lag+health 기준으로. 행수는 보조(DELETE 감지·표시)로 유지.
    long lag,          // sink consumer lag(미처리 변경이벤트 수). -1이면 sink 미소비(준비중).
    long endOffset,    // 토픽 end offset(% 계산용). -1이면 토픽/조회 불가.
    boolean sinkFailed // sink 커넥터/task FAILED 여부.
) {
    /** CDC(direct) 응답 생성. */
    public static SyncStatusResponse of(long sourceRows, long sinkRows, long delta, Instant checkedAt,
                                        long lag, long endOffset, boolean sinkFailed) {
        return new SyncStatusResponse(sourceRows, sinkRows, delta, checkedAt, true, lag, endOffset, sinkFailed);
    }

    /** EDA(fan-out) — sync 개념 없음. */
    public static SyncStatusResponse notApplicable() {
        return new SyncStatusResponse(-1, -1, -1, Instant.now(), false, -1, -1, false);
    }
}
