package com.bifrost.ops.workspace.kafka;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalCreateRequest;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaPrincipalServiceTest {

    @Mock private KafkaPrincipalRepository principalRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ProjectMemberRepository memberRepository;
    @Mock private WorkspaceAccessGuard accessGuard;

    private final UUID wsId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(userId, wsId, "admin@bifrost.io");

    private KafkaPrincipalService service() {
        return new KafkaPrincipalService(principalRepository, workspaceRepository, memberRepository, accessGuard);
    }

    @Test
    void listAllowsWorkspaceMembers() {
        KafkaPrincipalEntity entity = principal("team_user");
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(principalRepository.findByWorkspaceIdOrderByCreatedAtAsc(wsId)).thenReturn(List.of(entity));

        var out = service().list(wsId, principal);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).username()).isEqualTo("team_user");
        verify(accessGuard).requireAccess(wsId, principal);
    }

    @Test
    void createPersistsActivePrincipalForManager() {
        allowManager();
        when(principalRepository.existsByWorkspaceIdAndUsername(wsId, "team-user")).thenReturn(false);
        when(principalRepository.saveAndFlush(any(KafkaPrincipalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var out = service().create(wsId, principal,
                new KafkaPrincipalCreateRequest(" team-user ", " secret/team-user "));

        assertThat(out.username()).isEqualTo("team-user");
        assertThat(out.secretRef()).isEqualTo("secret/team-user");
        assertThat(out.status()).isEqualTo("ACTIVE");
    }

    @Test
    void createRejectsInvalidUsername() {
        allowManager();

        assertThatThrownBy(() -> service().create(wsId, principal,
                new KafkaPrincipalCreateRequest("bad.name", null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_USERNAME_INVALID));
    }

    @Test
    void createRejectsDuplicateUsername() {
        allowManager();
        when(principalRepository.existsByWorkspaceIdAndUsername(wsId, "team")).thenReturn(true);

        assertThatThrownBy(() -> service().create(wsId, principal,
                new KafkaPrincipalCreateRequest("team", null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_CONFLICT));
    }

    @Test
    void deactivateMarksInactive() {
        allowManager();
        UUID id = UUID.randomUUID();
        KafkaPrincipalEntity entity = principal("team");
        when(principalRepository.findByIdAndWorkspaceId(id, wsId)).thenReturn(Optional.of(entity));
        when(principalRepository.saveAndFlush(entity)).thenReturn(entity);

        var out = service().deactivate(wsId, principal, id);

        assertThat(out.status()).isEqualTo("INACTIVE");
        assertThat(out.deactivatedAt()).isNotNull();
    }

    @Test
    void revokeMarksRevokedAndDeactivated() {
        allowManager();
        UUID id = UUID.randomUUID();
        KafkaPrincipalEntity entity = principal("team");
        when(principalRepository.findByIdAndWorkspaceId(id, wsId)).thenReturn(Optional.of(entity));
        when(principalRepository.saveAndFlush(entity)).thenReturn(entity);

        var out = service().revoke(wsId, principal, id);

        assertThat(out.status()).isEqualTo("REVOKED");
        assertThat(out.revokedAt()).isNotNull();
        assertThat(out.deactivatedAt()).isNotNull();
    }

    @Test
    void rotateRejectsRevokedPrincipal() {
        allowManager();
        UUID id = UUID.randomUUID();
        KafkaPrincipalEntity entity = principal("team");
        entity.setStatus(KafkaPrincipalStatus.REVOKED);
        when(principalRepository.findByIdAndWorkspaceId(id, wsId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service().rotate(wsId, principal, id))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_ALREADY_REVOKED));
    }

    @Test
    void managerEndpointsRejectMemberRole() {
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(wsId, userId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(false);
        when(workspaceRepository.existsByIdAndOwnerUserId(wsId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service().create(wsId, principal,
                new KafkaPrincipalCreateRequest("team", null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
    }

    private void allowManager() {
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(wsId, userId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
    }

    private KafkaPrincipalEntity principal(String username) {
        KafkaPrincipalEntity entity = new KafkaPrincipalEntity();
        entity.setId(UUID.randomUUID());
        entity.setWorkspaceId(wsId);
        entity.setUsername(username);
        entity.setStatus(KafkaPrincipalStatus.ACTIVE);
        return entity;
    }
}
