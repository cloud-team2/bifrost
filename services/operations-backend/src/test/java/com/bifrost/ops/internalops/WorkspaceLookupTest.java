package com.bifrost.ops.internalops;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceLookupTest {

    private final WorkspaceRepository repository = mock(WorkspaceRepository.class);

    @Test
    void resolvesWorkspaceByUuidWhenProjectIdIsUuid() {
        UUID id = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(id, "proj-001");
        when(repository.findById(id)).thenReturn(Optional.of(workspace));

        Optional<WorkspaceEntity> resolved = WorkspaceLookup.resolve(repository, id.toString());

        assertThat(resolved).containsSame(workspace);
        verify(repository, never()).findByNamespace(id.toString());
    }

    @Test
    void resolvesWorkspaceByNamespaceWhenProjectIdIsSlug() {
        WorkspaceEntity workspace = workspace(UUID.randomUUID(), "proj-001");
        when(repository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace));

        Optional<WorkspaceEntity> resolved = WorkspaceLookup.resolve(repository, "proj-001");

        assertThat(resolved).containsSame(workspace);
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fallsBackToNamespaceWhenUuidShapedButNoWorkspaceById() {
        // namespace 슬러그 규칙(^[a-z0-9]([-a-z0-9]*[a-z0-9])?$)은 UUID 문자열도 통과시킬 수 있어,
        // findById가 비면 namespace 조회로 폴백해야 한다.
        UUID id = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(UUID.randomUUID(), id.toString());
        when(repository.findById(id)).thenReturn(Optional.empty());
        when(repository.findByNamespace(id.toString())).thenReturn(Optional.of(workspace));

        Optional<WorkspaceEntity> resolved = WorkspaceLookup.resolve(repository, id.toString());

        assertThat(resolved).containsSame(workspace);
    }

    @Test
    void returnsEmptyForNullOrBlankProjectId() {
        assertThat(WorkspaceLookup.resolve(repository, null)).isEmpty();
        assertThat(WorkspaceLookup.resolve(repository, "  ")).isEmpty();
    }

    private static WorkspaceEntity workspace(UUID id, String namespace) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setName(namespace);
        workspace.setNamespace(namespace);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }
}
