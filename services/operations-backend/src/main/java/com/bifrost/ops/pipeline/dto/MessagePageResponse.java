package com.bifrost.ops.pipeline.dto;

import java.util.List;

/**
 * 메시지 브라우저 페이지(#509). 단일 파티션을 오프셋 구간으로 결정적으로 읽는다.
 *
 * <p>기존 {@code /messages}(전 파티션 최근 N 머지)와 달리, 특정 파티션의 임의 오프셋 윈도우
 * {@code [startOffset, startOffset+limit)}를 읽어 과거 메시지까지 페이징으로 열람한다.
 * {@code beginOffset}/{@code endOffset}은 파티션의 가장 오래된/다음 produce 위치로, 프론트가
 * 이전/다음 페이지 가능 여부를 계산하는 데 쓴다. Kafka 미연결 시 빈 records + 0 offset을 반환한다.
 */
public record MessagePageResponse(
    List<KafkaMessageRecord> records,
    int partition,
    long startOffset,   // 이 페이지가 읽기 시작한 offset
    long beginOffset,   // 파티션 begin(가장 오래된 offset)
    long endOffset,     // 파티션 end(다음 produce offset)
    boolean hasOlder,   // 더 오래된 페이지 존재
    boolean hasNewer    // 더 최신 페이지 존재
) {
    public static MessagePageResponse empty(int partition) {
        return new MessagePageResponse(List.of(), partition, 0, 0, 0, false, false);
    }
}
