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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    private static final String MANAGER_EMAIL = "manager@bifrost.io";
    private static final String OWNER_EMAIL = "owner@bifrost.io";
    private static final String ADMIN_EMAIL = "admin@bifrost.io";
    private static final String MEMBER_EMAIL = "member@bifrost.io";
    private static final String OUTSIDER_EMAIL = "outsider@bifrost.io";
    private static final String MISSING_EMAIL = "missing@bifrost.io";
    private static final String OWNER_REQUEST_EMAIL = "owner-request@bifrost.io";
    private static final String PASSWORD_HASH = "hash";
    private static final String WORKSPACE_ACCESS_DENIED_MESSAGE = "워크스페이스 접근 권한이 없습니다";

    @Mock private ProjectMemberRepository memberRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceAccessGuard accessGuard;

    private ProjectMemberService service() {
        return new ProjectMemberService(memberRepository, workspaceRepository, userRepository, accessGuard);
    }

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final AuthenticatedUser manager = new AuthenticatedUser(managerId, workspaceId, MANAGER_EMAIL);

    @Test
    void listAllowsOwner() {
        UUID ownerId = UUID.randomUUID();
        AuthenticatedUser owner = new AuthenticatedUser(ownerId, workspaceId, OWNER_EMAIL);
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, ownerId, Role.OWNER);
        when(memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(List.of(ownerId))).thenReturn(List.of(user(ownerId, OWNER_EMAIL)));

        List<ProjectMemberResponse> out = service().list(workspaceId, owner);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.OWNER);
        verify(accessGuard).requireMember(workspaceId, owner);
    }

    @Test
    void listAllowsAdmin() {
        UUID adminId = UUID.randomUUID();
        AuthenticatedUser admin = new AuthenticatedUser(adminId, workspaceId, ADMIN_EMAIL);
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, adminId, Role.ADMIN);
        when(memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(List.of(adminId))).thenReturn(List.of(user(adminId, ADMIN_EMAIL)));

        List<ProjectMemberResponse> out = service().list(workspaceId, admin);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.ADMIN);
        verify(accessGuard).requireMember(workspaceId, admin);
    }

    @Test
    void listAllowsMember() {
        UUID memberId = UUID.randomUUID();
        AuthenticatedUser memberPrincipal = new AuthenticatedUser(memberId, workspaceId, MEMBER_EMAIL);
        ProjectMemberEntity member = new ProjectMemberEntity(workspaceId, memberId, Role.MEMBER);
        when(memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(List.of(memberId))).thenReturn(List.of(user(memberId, MEMBER_EMAIL)));

        List<ProjectMemberResponse> out = service().list(workspaceId, memberPrincipal);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.MEMBER);
        verify(accessGuard).requireMember(workspaceId, memberPrincipal);
    }

    @Test
    void listRejectsNonMember() {
        UUID outsiderId = UUID.randomUUID();
        AuthenticatedUser outsider = new AuthenticatedUser(outsiderId, UUID.randomUUID(), OUTSIDER_EMAIL);
        doThrow(new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, WORKSPACE_ACCESS_DENIED_MESSAGE))
                .when(accessGuard).requireMember(workspaceId, outsider);

        assertThatThrownBy(() -> service().list(workspaceId, outsider))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
    }

    @Test
    void addCreatesMemberByEmailWhenManager() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, MEMBER_EMAIL);
        allowManagerToAdd(userId, user, MEMBER_EMAIL);

        ProjectMemberResponse response = service().add(
                workspaceId, manager, new ProjectMemberAddRequest(MEMBER_EMAIL, Role.MEMBER));

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo(MEMBER_EMAIL);
        assertThat(response.role()).isEqualTo(Role.MEMBER);
        assertSavedMember(userId, Role.MEMBER);
    }

    @Test
    void addDowngradesOwnerRequestToAdmin() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, OWNER_REQUEST_EMAIL);
        allowManagerToAdd(userId, user, OWNER_REQUEST_EMAIL);

        ProjectMemberResponse response = service().add(
                workspaceId, manager, new ProjectMemberAddRequest(OWNER_REQUEST_EMAIL, Role.OWNER));

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo(OWNER_REQUEST_EMAIL);
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertSavedMember(userId, Role.ADMIN);
    }

    @Test
    void addRejectsDuplicateMember() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, MEMBER_EMAIL);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail(MEMBER_EMAIL)).thenReturn(Optional.of(user));
        when(memberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(true);

        assertThatThrownBy(() -> service().add(
                workspaceId, manager, new ProjectMemberAddRequest(MEMBER_EMAIL, Role.MEMBER)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.MEMBER_ALREADY_EXISTS));
    }

    @Test
    void addRejectsUnknownEmail() {
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail(MISSING_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().add(
                workspaceId, manager, new ProjectMemberAddRequest(MISSING_EMAIL, Role.MEMBER)))
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
        UserEntity user = user(userId, MEMBER_EMAIL);
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
        user.setPasswordHash(PASSWORD_HASH);
        return user;
    }

    private void allowManagerToAdd(UUID userId, UserEntity user, String email) {
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(workspaceId, managerId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(memberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)).thenReturn(false);
        when(memberRepository.saveAndFlush(any(ProjectMemberEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void assertSavedMember(UUID userId, Role role) {
        ArgumentCaptor<ProjectMemberEntity> saved = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        ProjectMemberEntity savedMember = saved.getValue();
        assertThat(savedMember.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(savedMember.getUserId()).isEqualTo(userId);
        assertThat(savedMember.getRole()).isEqualTo(role);
    }
}
