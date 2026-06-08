package com.bifrost.ops.workspace;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 워크스페이스 scope 인가의 단일 출처(#72).
 *
 * <p>소유 기반 다중 워크스페이스 모델에서 경로의 {@code wsId}가 인증 사용자가 접근 가능한
 * 워크스페이스인지 검증한다. 모든 {@code /api/v1/workspaces/{wsId}/**} 도메인 컨트롤러
 * (database·pipeline 등)는 이 가드를 통해 동일 규칙을 공유한다.
 *
 * <p>접근 허용 조건(둘 중 하나):
 * <ol>
 *   <li>fast-path: {@code wsId == principal.tenantId()} — JWT가 가리키는 home 워크스페이스는 항상 허용
 *       (DB 조회 없이 통과, 가장 흔한 경로).</li>
 *   <li>소유: {@code tenants.owner_user_id == principal.userId()} — 사용자가 생성/소유한 다른 워크스페이스.</li>
 * </ol>
 */
@Component
public class WorkspaceAccessGuard {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceAccessGuard(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    /** 접근 불가 시 예외를 던진다. 통과하면 void. */
    public void requireAccess(UUID wsId, AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        if (wsId.equals(principal.tenantId())) {
            return;
        }
        if (workspaceRepository.existsByIdAndOwnerUserId(wsId, principal.userId())) {
            return;
        }
        throw new ApiException(ErrorCode.RESOURCE_NOT_OWNED_BY_PROJECT,
                "워크스페이스 접근 권한이 없습니다");
    }
}
