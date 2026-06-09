package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.dto.ProjectMemberAddRequest;
import com.bifrost.ops.workspace.dto.ProjectMemberResponse;
import com.bifrost.ops.workspace.dto.ProjectMemberUpdateRequest;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    @Mock private ProjectMemberRepository memberRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceAccessGuard accessGuard;

    private ProjectMemberService service() {
        return new ProjectMemberService(memberRepository, workspaceRepository, userRepository, accessGuard);
    }

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final AuthenticatedUser manager = new AuthenticatedUser(managerId, workspaceId, "owner@bifrost.io");

    @Test
    void listAllowsOwner() {
        UUID ownerId = UUID.randomUUID();
        AuthenticatedUser owner = new AuthenticatedUser(ownerId, workspaceId, "owner@bifrost.io");
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, ownerId, Role.OWNER);
        when(memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(List.of(ownerId))).thenReturn(List.of(user(ownerId, "owner@bifrost.io")));

        List<ProjectMemberResponse> out = service().list(workspaceId, owner);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.OWNER);
        verify(accessGuard).requireMember(workspaceId, owner);
    }

    @Test
    void listAllowsAdmin() {
        UUID adminId = UUID.randomUUID();
        AuthenticatedUser admin = new AuthenticatedUser(adminId, workspaceId, "admin@bifrost.io");
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, adminId, Role.ADMIN);
        when(memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(List.of(adminId))).thenReturn(List.of(user(adminId, "admin@bifrost.io")));

        List<ProjectMemberResponse> out = service().list(workspaceId, admin);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.ADMIN);
        verify(accessGuard).requireMember(workspaceId, admin);
    }

    @Test
    void listAllowsMember() {
        UUID memberId = UUID.randomUUID();
        AuthenticatedUser memberPrincipal = new AuthenticatedUser(memberId, workspaceId, "member@bifrost.io");
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, memberId, Role.MEMBER);
        when(memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(List.of(memberId))).thenReturn(List.of(user(memberId, "member@bifrost.io")));

        List<ProjectMemberResponse> out = service().list(workspaceId, memberPrincipal);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.MEMBER);
        verify(accessGuard).requireMember(workspaceId, memberPrincipal);
    }

    @Test
    void listRejectsNonMember() {
        UUID outsiderId = UUID.randomUUID();
        AuthenticatedUser outsider = new AuthenticatedUser(outsiderId, UUID.randomUUID(), "outsider@bifrost.io");
        doThrow(new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, "워크스페이스 접근 권한이 없습니다"))
                .when(accessGuard).requireMember(workspaceId, outsider);

        assertThatThrownBy(() -> service().list(workspaceId, outsider))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
    }

    @Test
    void addCreatesMemberByEmailWhenManager() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, "member@bifrost.io");
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail("member@bifrost.io")).thenReturn(Optional.of(user));
        when(memberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(false);
        when(memberRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(ProjectMemberEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberResponse response = service().add(
                workspaceId, manager, new ProjectMemberAddRequest("member@bifrost.io", Role.MEMBER));

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo("member@bifrost.io");
        assertThat(response.role()).isEqualTo(Role.MEMBER);
        ArgumentCaptor<ProjectMemberEntity> saved = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void addDowngradesOwnerRequestToAdmin() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, "owner-request@bifrost.io");
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail("owner-request@bifrost.io")).thenReturn(Optional.of(user));
        when(memberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(false);
        when(memberRepository.saveAndFlush(any(ProjectMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberResponse response = service().add(
                workspaceId, manager, new ProjectMemberAddRequest("owner-request@bifrost.io", Role.OWNER));

        assertThat(response.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void addRejectsDuplicateMember() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, "member@bifrost.io");
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail("member@bifrost.io")).thenReturn(Optional.of(user));
        when(memberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(true);

        assertThatThrownBy(() -> service().add(
                workspaceId, manager, new ProjectMemberAddRequest("member@bifrost.io", Role.MEMBER)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.MEMBER_ALREADY_EXISTS));
    }

    @Test
    void addRejectsUnknownEmail() {
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail("missing@bifrost.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().add(
                workspaceId, manager, new ProjectMemberAddRequest("missing@bifrost.io", Role.MEMBER)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.USER_NOT_FOUND_BY_EMAIL));
    }

    @Test
    void updateRejectsOwnerDemotion() {
        UUID ownerId = UUID.randomUUID();
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(memberRepository.findByIdWorkspaceIdAndIdUserId(workspaceId, ownerId))
                .thenReturn(Optional.of(new ProjectMemberEntity(workspaceId, ownerId, Role.OWNER)));

        assertThatThrownBy(() -> service().update(
                workspaceId, ownerId, manager, new ProjectMemberUpdateRequest(Role.MEMBER)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.OWNER_DEMOTION_FORBIDDEN));
    }

    @Test
    void updateChangesNonOwnerRole() {
        UUID userId = UUID.randomUUID();
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, userId, Role.MEMBER);
        UserEntity user = user(userId, "member@bifrost.io");
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(memberRepository.findByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(Optional.of(member));
        when(memberRepository.saveAndFlush(member)).thenReturn(member);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        ProjectMemberResponse response = service().update(
                workspaceId, userId, manager, new ProjectMemberUpdateRequest(Role.ADMIN));

        assertThat(response.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void removeRejectsMissingMember() {
        UUID userId = UUID.randomUUID();
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(memberRepository.findByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().remove(workspaceId, userId, manager))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
    }

    private static UserEntity user(UUID id, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setTenantId(UUID.randomUUID());
        user.setPasswordHash("hash");
        return user;
    }
}
