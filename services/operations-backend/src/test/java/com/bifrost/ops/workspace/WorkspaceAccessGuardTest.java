package com.bifrost.ops.workspace;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceAccessGuardTest {

    private final WorkspaceRepository repo = mock(WorkspaceRepository.class);
    private final ProjectMemberRepository memberRepo = mock(ProjectMemberRepository.class);
    private final WorkspaceAccessGuard guard = new WorkspaceAccessGuard(repo, memberRepo);

    @Test
    void allowsHomeWorkspaceWithoutDbLookup() {
        UUID wsId = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        assertThatCode(() -> guard.requireAccess(wsId, user)).doesNotThrowAnyException();
        verify(repo, never()).existsByIdAndOwnerUserId(wsId, user.userId());
    }

    @Test
    void allowsOwnedNonHomeWorkspace() {
        UUID home = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), home, "u@bifrost.io");
        when(memberRepo.existsByIdWorkspaceIdAndIdUserId(other, user.userId())).thenReturn(false);
        when(repo.existsByIdAndOwnerUserId(other, user.userId())).thenReturn(true);

        assertThatCode(() -> guard.requireAccess(other, user)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWorkspaceNotOwned() {
        UUID home = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), home, "u@bifrost.io");
        when(memberRepo.existsByIdWorkspaceIdAndIdUserId(other, user.userId())).thenReturn(false);
        when(repo.existsByIdAndOwnerUserId(other, user.userId())).thenReturn(false);

        assertThatThrownBy(() -> guard.requireAccess(other, user))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
    }

    @Test
    void allowsMemberWorkspace() {
        UUID home = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), home, "u@bifrost.io");
        when(memberRepo.existsByIdWorkspaceIdAndIdUserId(other, user.userId())).thenReturn(true);

        assertThatCode(() -> guard.requireAccess(other, user)).doesNotThrowAnyException();
        verify(repo, never()).existsByIdAndOwnerUserId(other, user.userId());
    }

    @Test
    void rejectsUnauthenticated() {
        assertThatThrownBy(() -> guard.requireAccess(UUID.randomUUID(), null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
