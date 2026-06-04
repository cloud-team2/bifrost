package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.port.TenantProvisionerPort;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class WorkspaceServiceTest {

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private TenantProvisionerPort tenantProvisioner;
    @Mock private WorkspaceAccessGuard accessGuard;

    private WorkspaceService service() {
        return new WorkspaceService(workspaceRepository, tenantProvisioner, accessGuard);
    }

    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(userId, UUID.randomUUID(), "u@bifrost.io");

    @Test
    void listReturnsOwnedWorkspaces() {
        when(workspaceRepository.findByOwnerUserIdOrderByCreatedAt(userId))
                .thenReturn(List.of(entity(UUID.randomUUID(), "Team A", "team-a")));

        List<WorkspaceResponse> out = service().list(principal);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).name()).isEqualTo("Team A");
        assertThat(out.get(0).projectKey()).isEqualTo("team-a");
    }

    @Test
    void createGeneratesSlugLinksOwnerAndTriggersProvisioning() {
        when(workspaceRepository.existsByName("Orders Team")).thenReturn(false);
        when(workspaceRepository.existsByNamespace(any())).thenReturn(false);
        when(workspaceRepository.saveAndFlush(any(WorkspaceEntity.class)))
                .thenAnswer(inv -> { WorkspaceEntity w = inv.getArgument(0); w.setId(UUID.randomUUID()); return w; });

        WorkspaceResponse out = service().create(principal, new WorkspaceCreateRequest("Orders Team"));

        assertThat(out.projectKey()).isEqualTo("orders-team");
        assertThat(out.status()).isEqualTo(WorkspaceEntity.Status.PROVISIONING);

        ArgumentCaptor<WorkspaceEntity> saved = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getNamespace()).isEqualTo("orders-team");

        ArgumentCaptor<TenantProvisionRequest> prov = ArgumentCaptor.forClass(TenantProvisionRequest.class);
        verify(tenantProvisioner).provision(prov.capture());
        assertThat(prov.getValue().namespace()).isEqualTo("orders-team");
    }

    @Test
    void createRejectsDuplicateName() {
        when(workspaceRepository.existsByName("Team A")).thenReturn(true);

        assertThatThrownBy(() -> service().create(principal, new WorkspaceCreateRequest("Team A")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_NAME_CONFLICT));
    }

    @Test
    void getReturnsWorkspaceAfterScopeCheck() {
        UUID wsId = UUID.randomUUID();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(entity(wsId, "Team A", "team-a")));

        WorkspaceResponse out = service().get(wsId, principal);

        assertThat(out.id()).isEqualTo(wsId);
        verify(accessGuard).requireAccess(wsId, principal);
    }

    @Test
    void getThrowsWhenMissing() {
        UUID wsId = UUID.randomUUID();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(wsId, principal))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    private static WorkspaceEntity entity(UUID id, String name, String namespace) {
        WorkspaceEntity w = new WorkspaceEntity();
        w.setId(id);
        w.setName(name);
        w.setNamespace(namespace);
        w.setStatus(WorkspaceEntity.Status.PROVISIONING);
        return w;
    }
}
