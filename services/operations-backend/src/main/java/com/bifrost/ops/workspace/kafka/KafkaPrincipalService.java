package com.bifrost.ops.workspace.kafka;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalCreateRequest;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalResponse;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KafkaPrincipalService {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_-]{1,255}$");

    private final KafkaPrincipalRepository principalRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectMemberRepository memberRepository;
    private final WorkspaceAccessGuard accessGuard;

    public KafkaPrincipalService(KafkaPrincipalRepository principalRepository,
                                 WorkspaceRepository workspaceRepository,
                                 ProjectMemberRepository memberRepository,
                                 WorkspaceAccessGuard accessGuard) {
        this.principalRepository = principalRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public List<KafkaPrincipalResponse> list(UUID wsId, AuthenticatedUser principal) {
        requireWorkspaceAccess(wsId, principal);
        return principalRepository.findByWorkspaceIdOrderByCreatedAtAsc(wsId).stream()
                .map(KafkaPrincipalResponse::from)
                .toList();
    }

    @Transactional
    public KafkaPrincipalResponse create(UUID wsId, AuthenticatedUser principal, KafkaPrincipalCreateRequest req) {
        requireManager(wsId, principal);
        String username = normalizeUsername(req.username());
        if (principalRepository.existsByWorkspaceIdAndUsername(wsId, username)) {
            throw conflict(username);
        }

        KafkaPrincipalEntity entity = new KafkaPrincipalEntity();
        entity.setWorkspaceId(wsId);
        entity.setUsername(username);
        entity.setSecretRef(normalizeNullable(req.secretRef()));
        entity.setStatus(KafkaPrincipalStatus.ACTIVE);
        try {
            return KafkaPrincipalResponse.from(principalRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw conflict(username);
        }
    }

    @Transactional
    public KafkaPrincipalResponse deactivate(UUID wsId, AuthenticatedUser principal, UUID id) {
        requireManager(wsId, principal);
        KafkaPrincipalEntity entity = load(wsId, id);
        requireNotRevoked(entity);
        entity.setStatus(KafkaPrincipalStatus.INACTIVE);
        entity.setDeactivatedAt(Instant.now());
        return KafkaPrincipalResponse.from(principalRepository.saveAndFlush(entity));
    }

    @Transactional
    public KafkaPrincipalResponse revoke(UUID wsId, AuthenticatedUser principal, UUID id) {
        requireManager(wsId, principal);
        KafkaPrincipalEntity entity = load(wsId, id);
        requireNotRevoked(entity);
        Instant now = Instant.now();
        entity.setStatus(KafkaPrincipalStatus.REVOKED);
        entity.setRevokedAt(now);
        if (entity.getDeactivatedAt() == null) {
            entity.setDeactivatedAt(now);
        }
        return KafkaPrincipalResponse.from(principalRepository.saveAndFlush(entity));
    }

    @Transactional
    public KafkaPrincipalResponse rotate(UUID wsId, AuthenticatedUser principal, UUID id) {
        requireManager(wsId, principal);
        KafkaPrincipalEntity entity = load(wsId, id);
        requireNotRevoked(entity);
        entity.setSecretRef("kafka-principal/" + wsId + "/" + id + "/" + UUID.randomUUID());
        return KafkaPrincipalResponse.from(principalRepository.saveAndFlush(entity));
    }

    private KafkaPrincipalEntity load(UUID wsId, UUID id) {
        return principalRepository.findByIdAndWorkspaceId(id, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND,
                        "Kafka principal을 찾을 수 없습니다"));
    }

    private void requireWorkspaceAccess(UUID wsId, AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        if (!workspaceRepository.existsById(wsId)) {
            throw new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다");
        }
    }

    private void requireManager(UUID wsId, AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        if (!workspaceRepository.existsById(wsId)) {
            throw new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다");
        }
        boolean hasRole = memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(
                wsId, principal.userId(), List.of(Role.OWNER, Role.ADMIN));
        if (hasRole || workspaceRepository.existsByIdAndOwnerUserId(wsId, principal.userId())) {
            return;
        }
        throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, "워크스페이스 관리 권한이 없습니다");
    }

    private static String normalizeUsername(String username) {
        String normalized = normalizeNullable(username);
        if (normalized == null || !USERNAME.matcher(normalized).matches()) {
            throw new ApiException(ErrorCode.KAFKA_PRINCIPAL_USERNAME_INVALID,
                    "Kafka principal username 형식이 올바르지 않습니다");
        }
        return normalized;
    }

    private static void requireNotRevoked(KafkaPrincipalEntity entity) {
        if (entity.getStatus() == KafkaPrincipalStatus.REVOKED) {
            throw new ApiException(ErrorCode.KAFKA_PRINCIPAL_ALREADY_REVOKED,
                    "이미 revoked 상태인 Kafka principal입니다");
        }
    }

    private static ApiException conflict(String username) {
        return new ApiException(ErrorCode.KAFKA_PRINCIPAL_CONFLICT,
                "이미 사용 중인 Kafka principal username입니다: " + username);
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
