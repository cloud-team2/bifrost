package com.bifrost.ops.workspace.dto;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * 워크스페이스 응답(#72). {@code projectKey}는 namespace 슬러그로, 파이프라인 토픽 prefix에 쓰인다.
 */
public record WorkspaceResponse(
    UUID id,
    String name,
    String projectKey,
    WorkspaceEntity.Status status,
    Instant createdAt
) {
    public static WorkspaceResponse from(WorkspaceEntity w) {
        return new WorkspaceResponse(w.getId(), w.getName(), w.getNamespace(),
                w.getStatus(), w.getCreatedAt());
    }
}
