package com.bifrost.ops.pipeline.dto;

import java.util.List;

/**
 * 토픽 메타데이터 응답(#126). 파티션별 offset과 ISR 상태를 포함한다.
 *
 * @param isrPct       ISR/Replica 비율(0~100). 100이면 모든 파티션 정상.
 * @param retentionMs  보존 기간(ms). 설정 없으면 -1.
 */
public record TopicInfoResponse(
        String name,
        double isrPct,
        long retentionMs,
        List<PartitionDetail> partitions
) {
    public record PartitionDetail(int id, String leader, long beginOffset, long endOffset) {}
}
