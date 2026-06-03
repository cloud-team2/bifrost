package com.bifrost.ops.database.persistence.repository;

import java.util.UUID;

/**
 * {@code pipelines} 행의 읽기 전용 projection(#30). PipelineEntity가 아직 없어 native 쿼리
 * 결과를 인터페이스 projection으로 받는다(컬럼명 → getter 매핑).
 */
public interface PipelineSummaryRow {
    UUID getId();

    String getName();

    String getType();

    String getStatus();
}
