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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TenantProvisionerPort tenantProvisioner;

    public AuthService(UserRepository userRepository,
                       WorkspaceRepository workspaceRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TenantProvisionerPort tenantProvisioner) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tenantProvisioner = tenantProvisioner;
    }

    @Transactional
    public AuthTokensResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_USED, "이미 가입된 이메일");
        }
        if (workspaceRepository.existsByName(req.workspaceName())) {
            throw new ApiException(ErrorCode.WORKSPACE_NAME_CONFLICT, "이미 사용 중인 워크스페이스 이름");
        }
        if (workspaceRepository.existsByNamespace(req.namespace())) {
            throw new ApiException(ErrorCode.WORKSPACE_NAMESPACE_CONFLICT, "이미 사용 중인 namespace");
        }

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(req.workspaceName());
        workspace.setNamespace(req.namespace());
        workspace.setStatus(WorkspaceEntity.Status.PROVISIONING);

        UserEntity user = new UserEntity();
        // 소유 기반 다중 워크스페이스(#72): home 워크스페이스 소유자를 가입자로 연결하려면
        // workspace 저장 전에 user id가 필요하므로 미리 생성한다(추가 write 없이 단일 save 유지).
        user.setId(UUID.randomUUID());
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        workspace.setOwnerUserId(user.getId());

        try {
            workspace = workspaceRepository.saveAndFlush(workspace);
            user.setTenantId(workspace.getId());
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw mapRegistrationConflict(req, e);
        }

        triggerProvisioning(workspace);

        String token = jwtService.issue(user.getId(), workspace.getId(), user.getEmail());
        return new AuthTokensResponse(
            token, "Bearer", jwtService.ttl().toSeconds(), user.getId(), workspace.getId());
    }

    private ApiException mapRegistrationConflict(RegisterRequest req, DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : "";
        String normalized = detail.toLowerCase();
        if (normalized.contains("email")) {
            return new ApiException(ErrorCode.EMAIL_ALREADY_USED, "이미 가입된 이메일");
        }
        if (normalized.contains("namespace")) {
            return new ApiException(ErrorCode.WORKSPACE_NAMESPACE_CONFLICT, "이미 사용 중인 namespace");
        }
        if (normalized.contains("name")) {
            return new ApiException(ErrorCode.WORKSPACE_NAME_CONFLICT, "이미 사용 중인 워크스페이스 이름");
        }
        log.warn("회원가입 중 unique 제약 충돌 — 매핑 실패: {}", detail);
        return new ApiException(ErrorCode.EMAIL_ALREADY_USED, "이미 사용 중인 값이 있습니다");
    }

    public AuthTokensResponse login(LoginRequest req) {
        UserEntity user = userRepository.findByEmail(req.email())
            .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 올바르지 않습니다"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 올바르지 않습니다");
        }

        String token = jwtService.issue(user.getId(), user.getTenantId(), user.getEmail());
        return new AuthTokensResponse(
            token, "Bearer", jwtService.ttl().toSeconds(), user.getId(), user.getTenantId());
    }

    /** 액세스 토큰 재발급(#72, 최소 stub). 인증된 사용자에게 동일 scope의 새 토큰을 발급한다. */
    public AuthTokensResponse refresh(AuthenticatedUser principal) {
        String token = jwtService.issue(principal.userId(), principal.tenantId(), principal.email());
        return new AuthTokensResponse(
            token, "Bearer", jwtService.ttl().toSeconds(), principal.userId(), principal.tenantId());
    }

    public MeResponse me(AuthenticatedUser principal) {
        WorkspaceEntity workspace = workspaceRepository.findById(principal.tenantId())
            .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
        return new MeResponse(
            principal.userId(),
            principal.email(),
            workspace.getId(),
            workspace.getName(),
            workspace.getNamespace(),
            workspace.getStatus()
        );
    }

    private void triggerProvisioning(WorkspaceEntity workspace) {
        TenantProvisionRequest req = new TenantProvisionRequest(
            workspace.getId(),
            workspace.getNamespace(),
            TenantProvisionRequest.ResourceQuotaSpec.defaultQuota()
        );
        try {
            tenantProvisioner.provision(req);
        } catch (UnsupportedOperationException e) {
            log.warn("TenantProvisioner 미구현 — workspace {} 는 PROVISIONING 상태로 남는다", workspace.getId());
        } catch (Exception e) {
            log.error("TenantProvisioner 호출 실패 — workspace {}", workspace.getId(), e);
        }
    }
}
