package com.bifrost.ops.database.dto;

/**
 * 이 DB를 쓰는 파이프라인 요약(#30, FR-018). pipelines 테이블에서 조회한다.
 *
 * @param type   파이프라인 유형(CDC/EDA 등)
 * @param status 파이프라인 상태
 */
public record DatabasePipelineSummary(
        String id,
        String name,
        String type,
        String status
) {
}
