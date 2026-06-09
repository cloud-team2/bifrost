package com.bifrost.ops.global.bootstrap;

import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberEntity;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberId;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevDataSeederTest {

    private static final String EMAIL = "ta@bifrost.io";
    private static final String PASSWORD = "ta123456";
    private static final String WORKSPACE_NAME = "Demo Team";
    private static final String NAMESPACE = "demo-team";

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ProjectMemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private DevDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new DevDataSeeder(
                userRepository,
                workspaceRepository,
                memberRepository,
                passwordEncoder,
                true,
                EMAIL,
                PASSWORD,
                WORKSPACE_NAME,
                NAMESPACE
        );
    }

    @Test
    void backfillsOwnerMemberWhenDemoUserAlreadyExistsWithoutMemberRow() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserEntity user = demoUser(userId, workspaceId);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(memberRepository.existsById(new ProjectMemberId(workspaceId, userId))).thenReturn(false);

        seeder.run();

        ArgumentCaptor<ProjectMemberEntity> memberCaptor = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(memberRepository).saveAndFlush(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(userId);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(Role.OWNER);
        verify(workspaceRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void existingDemoUserMemberRowIsNoop() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserEntity user = demoUser(userId, workspaceId);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(memberRepository.existsById(new ProjectMemberId(workspaceId, userId))).thenReturn(true);

        seeder.run();

        verify(memberRepository, never()).saveAndFlush(any());
        verify(workspaceRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void newSeedStillCreatesOwnerMember() {
        UUID workspaceId = UUID.randomUUID();
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(workspaceRepository.existsByName(WORKSPACE_NAME)).thenReturn(false);
        when(workspaceRepository.existsByNamespace(NAMESPACE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");
        when(workspaceRepository.saveAndFlush(any(WorkspaceEntity.class)))
                .thenAnswer(invocation -> withWorkspaceId(invocation.getArgument(0), workspaceId));

        seeder.run();

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getTenantId()).isEqualTo(workspaceId);
        assertThat(userCaptor.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded-password");

        ArgumentCaptor<ProjectMemberEntity> memberCaptor = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(memberRepository).saveAndFlush(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(userCaptor.getValue().getId());
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(Role.OWNER);
    }

    private UserEntity demoUser(UUID userId, UUID workspaceId) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setTenantId(workspaceId);
        user.setEmail(EMAIL);
        user.setPasswordHash("encoded-password");
        return user;
    }

    private WorkspaceEntity withWorkspaceId(WorkspaceEntity workspace, UUID workspaceId) {
        workspace.setId(workspaceId);
        return workspace;
    }
}
