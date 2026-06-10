package com.bifrost.ops.internalops;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Agent tool의 {@code {projectId}} path 변수를 workspace로 해석한다(#423).
 *
 * <p>프론트엔드 챗봇·ai-service는 project_id로 workspace UUID를 보내고(시스템 canonical 형식,
 * RunRecord·지식검색 스코프와 동일), 기존 internal-ops 호출자는 namespace 슬러그를 보낸다.
 * 두 형식을 모두 수용해 "프로젝트를 찾을 수 없습니다" 미스매치를 막는다.
 *
 * <p>UUID 파싱이 가능하면 {@code findById}를 먼저 시도하고(canonical), 없으면 namespace로 폴백한다.
 */
public final class WorkspaceLookup {

    private WorkspaceLookup() {
    }

    /** projectId(UUID 또는 namespace 슬러그)로 workspace를 조회한다. */
    public static Optional<WorkspaceEntity> resolve(WorkspaceRepository repository, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        UUID id = tryParseUuid(projectId);
        if (id != null) {
            Optional<WorkspaceEntity> byId = repository.findById(id);
            if (byId.isPresent()) {
                return byId;
            }
        }
        return repository.findByNamespace(projectId);
    }

    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
