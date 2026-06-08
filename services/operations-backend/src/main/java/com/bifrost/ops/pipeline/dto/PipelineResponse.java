package com.bifrost.ops.pipeline.dto;

import com.bifrost.ops.pipeline.PipelinePatternCodec;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * 파이프라인 응답(#71). 상태는 부록 B.1 소문자 값(creating/active/lag/error/paused),
 * pattern은 프론트 표현(fan-out/direct). topic 이름은 Connection Guide 외 화면 노출 X지만
 * 상세 응답에는 포함한다(프론트가 표시 여부를 결정).
 */
public record PipelineResponse(
    UUID id,
    String name,
    String pattern,
    String status,
    String statusMessage,
    UUID sourceDbId,
    UUID sinkDbId,
    String schema,
    String table,
    String topic,
    String sourceConnector,
    String sinkConnector,
    Instant createdAt
) {
    public static PipelineResponse from(PipelineEntity p) {
        return new PipelineResponse(
            p.getId(),
            p.getName(),
            PipelinePatternCodec.toApi(p.getPattern()),
            p.getStatus().name().toLowerCase(),
            p.getStatusMessage(),
            p.getSourceDatasourceId(),
            p.getSinkDatasourceId(),
            p.getSchemaName(),
            p.getTableName(),
            p.getTopicName(),
            p.getSourceConnectorName(),
            p.getSinkConnectorName(),
            p.getCreatedAt()
        );
    }
}
