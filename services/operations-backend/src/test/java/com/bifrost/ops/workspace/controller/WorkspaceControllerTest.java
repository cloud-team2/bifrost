package com.bifrost.ops.workspace.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.ProjectMemberAddRequest;
import com.bifrost.ops.workspace.dto.ProjectMemberResponse;
import com.bifrost.ops.workspace.dto.ProjectMemberUpdateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.dto.WorkspaceUpdateRequest;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.service.ProjectMemberService;
import com.bifrost.ops.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceControllerTest {

    private final WorkspaceService service = mock(WorkspaceService.class);
    private final ProjectMemberService memberService = mock(ProjectMemberService.class);
    private final WorkspaceController controller = new WorkspaceController(service, memberService);

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
    void updateDelegatesPatchWorkspace() {
        UUID wsId = UUID.randomUUID();
        WorkspaceUpdateRequest req = new WorkspaceUpdateRequest("Team B", "Asia/Seoul");
        when(service.update(wsId, principal, req)).thenReturn(sample(wsId, "Team B"));

        WorkspaceResponse out = controller.update(wsId, principal, req);

        assertThat(out.id()).isEqualTo(wsId);
        assertThat(out.name()).isEqualTo("Team B");
        verify(service).update(wsId, principal, req);
    }

    @Test
    void memberEndpointsDelegateWithExpectedStatusCodes() {
        UUID wsId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProjectMemberAddRequest addReq = new ProjectMemberAddRequest("member@bifrost.io", Role.MEMBER);
        ProjectMemberUpdateRequest updateReq = new ProjectMemberUpdateRequest(Role.ADMIN);
        ProjectMemberResponse member = new ProjectMemberResponse(wsId, userId, "member@bifrost.io", Role.MEMBER, Instant.now());
        when(memberService.list(wsId, principal)).thenReturn(List.of(member));
        when(memberService.add(eq(wsId), eq(principal), eq(addReq))).thenReturn(member);
        when(memberService.update(wsId, userId, principal, updateReq)).thenReturn(member);

        assertThat(controller.listMembers(wsId, principal)).hasSize(1);
        assertThat(controller.addMember(wsId, principal, addReq).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.updateMember(wsId, userId, principal, updateReq).userId()).isEqualTo(userId);
        assertThat(controller.removeMember(wsId, userId, principal).getStatusCode().value()).isEqualTo(204);
        assertThatThrownBy(() -> controller.listMembers(wsId, null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> controller.addMember(wsId, null, addReq))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> controller.updateMember(wsId, userId, null, updateReq))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> controller.removeMember(wsId, userId, null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verify(memberService).remove(wsId, userId, principal);
    }

    @Test
    void rejectsUnauthenticated() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private static WorkspaceResponse sample(UUID id, String name) {
        return new WorkspaceResponse(id, name, name.toLowerCase().replace(' ', '-'),
                null, WorkspaceEntity.Status.PROVISIONING, Instant.now(), 0L, 0L);
    }
}
