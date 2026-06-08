package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * 워크스페이스 응답(#72). {@code projectKey}는 namespace 슬러그로, 파이프라인 토픽 prefix에 쓰인다.
 *
 * <p>{@code pipelineCount}/{@code activePipelineCount}는 프로젝트 목록 카드의 요약 표시용(#105).
 * 목록/상세에서 채워지고, 생성 직후({@link #from(WorkspaceEntity)})에는 0이다.
 */
public record WorkspaceResponse(
    UUID id,
    String name,
    String projectKey,
    String timezone,
    WorkspaceEntity.Status status,
    Instant createdAt,
    long pipelineCount,
    long activePipelineCount
) {
    /** 카운트 없이(생성 직후 등) — 0으로 채운다. */
    public static WorkspaceResponse from(WorkspaceEntity w) {
        return from(w, 0L, 0L);
    }

    public static WorkspaceResponse from(WorkspaceEntity w, long pipelineCount, long activePipelineCount) {
        return new WorkspaceResponse(w.getId(), w.getName(), w.getNamespace(),
                w.getTimezone(), w.getStatus(), w.getCreatedAt(), pipelineCount, activePipelineCount);
    }
}
