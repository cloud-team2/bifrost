package com.bifrost.ops.auth.service;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.auth.dto.AuthTokensResponse;
import com.bifrost.ops.auth.dto.LoginRequest;
import com.bifrost.ops.auth.dto.MeResponse;
import com.bifrost.ops.auth.dto.RegisterRequest;
import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.port.TenantProvisionerPort;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private TenantProvisionerPort tenantProvisioner;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
            userRepository,
            workspaceRepository,
            passwordEncoder,
            jwtService,
            tenantProvisioner
        );
    }

    @Test
    void registerSavesUserAndWorkspaceAndIssuesToken() {
        RegisterRequest request = registerRequest();
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubAvailableRegistration(request);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(workspaceRepository.saveAndFlush(any(WorkspaceEntity.class)))
            .thenAnswer(invocation -> withWorkspaceId(invocation.getArgument(0), workspaceId));
        when(userRepository.saveAndFlush(any(UserEntity.class)))
            .thenAnswer(invocation -> withUserId(invocation.getArgument(0), userId));
        when(jwtService.issue(userId, workspaceId, "user@example.com")).thenReturn("access-token");
        when(jwtService.ttl()).thenReturn(Duration.ofHours(2));

        AuthTokensResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(7200);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.workspaceId()).isEqualTo(workspaceId);

        ArgumentCaptor<WorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceRepository).saveAndFlush(workspaceCaptor.capture());
        assertThat(workspaceCaptor.getValue().getName()).isEqualTo("Team A");
        assertThat(workspaceCaptor.getValue().getNamespace()).isEqualTo("team-a");
        assertThat(workspaceCaptor.getValue().getStatus()).isEqualTo(WorkspaceEntity.Status.PROVISIONING);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getTenantId()).isEqualTo(workspaceId);
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded-password");

        ArgumentCaptor<TenantProvisionRequest> provisionCaptor = ArgumentCaptor.forClass(TenantProvisionRequest.class);
        verify(tenantProvisioner).provision(provisionCaptor.capture());
        assertThat(provisionCaptor.getValue().tenantId()).isEqualTo(workspaceId);
        assertThat(provisionCaptor.getValue().namespace()).isEqualTo("team-a");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_USED));

        verify(workspaceRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsDuplicateNamespace() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(workspaceRepository.existsByName(request.workspaceName())).thenReturn(false);
        when(workspaceRepository.existsByNamespace(request.namespace())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.code()).isEqualTo(ErrorCode.WORKSPACE_NAMESPACE_CONFLICT));

        verify(workspaceRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerContinuesWhenTenantProvisionerThrows() {
        RegisterRequest request = registerRequest();
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubAvailableRegistration(request);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(workspaceRepository.saveAndFlush(any(WorkspaceEntity.class)))
            .thenAnswer(invocation -> withWorkspaceId(invocation.getArgument(0), workspaceId));
        when(userRepository.saveAndFlush(any(UserEntity.class)))
            .thenAnswer(invocation -> withUserId(invocation.getArgument(0), userId));
        doThrow(new UnsupportedOperationException("not implemented"))
            .when(tenantProvisioner).provision(any(TenantProvisionRequest.class));
        when(jwtService.issue(userId, workspaceId, "user@example.com")).thenReturn("access-token");
        when(jwtService.ttl()).thenReturn(Duration.ofMinutes(30));

        AuthTokensResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.expiresInSeconds()).isEqualTo(1800);
        verify(workspaceRepository).saveAndFlush(any(WorkspaceEntity.class));
        verify(userRepository).saveAndFlush(any(UserEntity.class));
        verify(tenantProvisioner).provision(any(TenantProvisionRequest.class));
    }

    @Test
    void loginIssuesTokenWhenPasswordMatches() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserEntity user = user(userId, tenantId, "user@example.com", "encoded-password");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true);
        when(jwtService.issue(userId, tenantId, "user@example.com")).thenReturn("access-token");
        when(jwtService.ttl()).thenReturn(Duration.ofHours(1));

        AuthTokensResponse response = authService.login(new LoginRequest("user@example.com", "password123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(3600);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.workspaceId()).isEqualTo(tenantId);
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("unknown@example.com", "password123")))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(jwtService, never()).issue(any(), any(), any());
    }

    @Test
    void loginRejectsWrongPassword() {
        UserEntity user = user(UUID.randomUUID(), UUID.randomUUID(), "user@example.com", "encoded-password");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong-password")))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(jwtService, never()).issue(any(), any(), any());
    }

    @Test
    void meThrowsWhenWorkspaceMissing() {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            authService.me(new AuthenticatedUser(userId, workspaceId, "user@example.com")))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.code()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    @Test
    void meReturnsWorkspaceAndUserInfo() {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(workspaceId, "Team A", "team-a", WorkspaceEntity.Status.ACTIVE);
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));

        MeResponse response = authService.me(new AuthenticatedUser(userId, workspaceId, "user@example.com"));

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.workspaceId()).isEqualTo(workspaceId);
        assertThat(response.workspaceName()).isEqualTo("Team A");
        assertThat(response.namespace()).isEqualTo("team-a");
        assertThat(response.workspaceStatus()).isEqualTo(WorkspaceEntity.Status.ACTIVE);
    }

    private static RegisterRequest registerRequest() {
        return new RegisterRequest("user@example.com", "password123", "Team A", "team-a");
    }

    private void stubAvailableRegistration(RegisterRequest request) {
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(workspaceRepository.existsByName(request.workspaceName())).thenReturn(false);
        when(workspaceRepository.existsByNamespace(request.namespace())).thenReturn(false);
    }

    private static WorkspaceEntity withWorkspaceId(WorkspaceEntity workspace, UUID workspaceId) {
        workspace.setId(workspaceId);
        return workspace;
    }

    private static UserEntity withUserId(UserEntity user, UUID userId) {
        user.setId(userId);
        return user;
    }

    private static UserEntity user(UUID userId, UUID tenantId, String email, String passwordHash) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        return user;
    }

    private static WorkspaceEntity workspace(UUID workspaceId, String name, String namespace, WorkspaceEntity.Status status) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setName(name);
        workspace.setNamespace(namespace);
        workspace.setStatus(status);
        return workspace;
    }
}
