package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
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

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    @Mock private ProjectMemberRepository memberRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private UserRepository userRepository;

    private ProjectMemberService service() {
        return new ProjectMemberService(memberRepository, workspaceRepository, userRepository);
    }

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final AuthenticatedUser manager = new AuthenticatedUser(managerId, workspaceId, "owner@bifrost.io");

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
