package com.bifrost.ops.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 파이프라인 생성 요청(#71, FR-004). 마법사 입력.
 *
 * <p>{@code pattern}은 {@code fan-out}(EDA) 또는 {@code direct}(CDC). DIRECT만 {@code sinkDbId} 필수.
 * 하나의 pipeline은 하나의 {@code schema.table}만 담당한다.
 */
public record PipelineCreateRequest(
    @NotBlank @Size(min = 1, max = 100) String name,
    @Size(max = 100) String alias,
    @NotBlank String pattern,
    @NotNull UUID sourceDbId,
    UUID sinkDbId,
    @NotBlank String schema,
    @NotBlank String table
) {
    /** 하위호환: alias 없는 기존 호출용. */
    public PipelineCreateRequest(
        String name, String pattern, UUID sourceDbId, UUID sinkDbId, String schema, String table) {
        this(name, null, pattern, sourceDbId, sinkDbId, schema, table);
    }
}
