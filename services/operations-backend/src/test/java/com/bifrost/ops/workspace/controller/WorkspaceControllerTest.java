package com.bifrost.ops.workspace.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceControllerTest {

    private final WorkspaceService service = mock(WorkspaceService.class);
    private final WorkspaceController controller = new WorkspaceController(service);

    private final AuthenticatedUser principal =
            new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "u@bifrost.io");

    @Test
    void listDelegates() {
        when(service.list(principal)).thenReturn(List.of(sample(UUID.randomUUID(), "Team A")));
        assertThat(controller.list(principal)).hasSize(1);
    }

    @Test
    void createReturns201() {
        WorkspaceCreateRequest req = new WorkspaceCreateRequest("Team A");
        WorkspaceResponse body = sample(UUID.randomUUID(), "Team A");
        when(service.create(principal, req)).thenReturn(body);

        ResponseEntity<WorkspaceResponse> resp = controller.create(principal, req);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().projectKey()).isEqualTo("team-a");
    }

    @Test
    void getDelegates() {
        UUID wsId = UUID.randomUUID();
        when(service.get(wsId, principal)).thenReturn(sample(wsId, "Team A"));
        assertThat(controller.get(wsId, principal).id()).isEqualTo(wsId);
    }

    @Test
    void rejectsUnauthenticated() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private static WorkspaceResponse sample(UUID id, String name) {
        return new WorkspaceResponse(id, name, name.toLowerCase().replace(' ', '-'),
                WorkspaceEntity.Status.PROVISIONING, Instant.now());
    }
}
