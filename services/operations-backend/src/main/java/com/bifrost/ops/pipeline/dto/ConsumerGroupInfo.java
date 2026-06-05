package com.bifrost.ops.pipeline.dto;

import java.util.List;

/**
 * Consumer group 상세 응답(#126). 토픽별 lag과 파티션 오프셋을 포함한다.
 *
 * @param lastCommit  가장 최근 commit timestamp(ms). 없으면 -1.
 */
public record ConsumerGroupInfo(
        String name,
        String state,
        int members,
        long totalLag,
        long lastCommit,
        List<PartitionOffset> partitionOffsets
) {
    public record PartitionOffset(int partition, String member, long committed, long endOffset) {}
}
