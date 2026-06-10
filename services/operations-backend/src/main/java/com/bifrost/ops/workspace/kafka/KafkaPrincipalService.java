package com.bifrost.ops.workspace.kafka;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalCreateRequest;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalResponse;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalSecretResponse;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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
    private final KubernetesClient k8s;
    private final AuditService auditService;
    private final String kafkaNamespace;

    public KafkaPrincipalService(KafkaPrincipalRepository principalRepository,
                                 WorkspaceRepository workspaceRepository,
                                 ProjectMemberRepository memberRepository,
                                 WorkspaceAccessGuard accessGuard,
                                 KubernetesClient k8s,
                                 AuditService auditService,
                                 @Value("${kafka-cluster.namespace:platform-kafka}") String kafkaNamespace) {
        this.principalRepository = principalRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.accessGuard = accessGuard;
        this.k8s = k8s;
        this.auditService = auditService;
        this.kafkaNamespace = kafkaNamespace;
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

    /**
     * KafkaUser Secret 조회(#303). OWNER/ADMIN·owner만 허용한다.
     *
     * <p>플랫폼 정책(server.md: secret 원문 read deny)에 따라 raw password는 절대 반환하지 않는다.
     * Secret의 존재·위치(namespace/name)·키 참조와 마스킹 password 표기만 제공한다(reference/masked only).
     *
     * <p>읽기 조회지만 {@link AuditService#record}로 감사 INSERT를 남기므로 readOnly 트랜잭션이 아니다
     * — readOnly면 Hibernate flush가 MANUAL이라 audit row가 commit되지 않고 조용히 유실된다.
     */
    @Transactional
    public KafkaPrincipalSecretResponse secret(UUID wsId, AuthenticatedUser principal, UUID id) {
        requireManager(wsId, principal);
        KafkaPrincipalEntity entity = load(wsId, id);
        requireActive(entity);
        WorkspaceEntity workspace = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
        String expectedSecretName = ConnectorNaming.kafkaUserName(workspace.getNamespace());
        if (!expectedSecretName.equals(entity.getUsername())) {
            throw new ApiException(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND,
                    "TenantProvisioner 규칙에 맞는 Kafka principal Secret을 찾을 수 없습니다");
        }
        String secretName = entity.getUsername();
        Secret secret = k8s.secrets().inNamespace(kafkaNamespace).withName(secretName).get();
        if (secret == null) {
            throw new ApiException(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND,
                    "Kafka principal Secret을 찾을 수 없습니다");
        }

        List<String> keys = secretKeys(secret);
        // 존재하는 모든 username alias를 각각 검증 — 하나라도 principal과 불일치하면 fail-closed
        // (sasl.username은 맞지만 username이 다른 값으로 꼬인 Secret 등 잘못된 자격증명 참조 방지)
        for (String alias : new String[]{"sasl.username", "username"}) {
            String aliasValue = secretValue(secret, alias);
            if (aliasValue != null && !aliasValue.equals(entity.getUsername())) {
                throw new ApiException(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND,
                        "Kafka principal Secret의 username이 principal과 일치하지 않습니다");
            }
        }
        // Secret이 자격증명을 갖췄는지 확인만 한다(존재 검증). raw 값은 읽되 응답·로그·audit 어디에도 노출하지 않는다.
        if (firstValue(secret, "sasl.password", "password") == null) {
            throw new ApiException(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND,
                    "Kafka principal Secret의 password key를 찾을 수 없습니다");
        }

        auditService.record(wsId, principal.email(), "KAFKA_PRINCIPAL_SECRET_VIEW", "KAFKA_PRINCIPAL", id,
                "username=" + entity.getUsername() + ", secretName=" + secretName);
        OpsLog.ok("Kafka", "principal Secret reference 조회",
                "principalId=" + id + ", username=" + entity.getUsername());

        return new KafkaPrincipalSecretResponse(
                entity.getId(),
                entity.getUsername(),
                entity.getStatus().name(),
                kafkaNamespace,
                secretName,
                keys,
                "********",
                Instant.now(),
                "MASKED_REFERENCE_ONLY");
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

    private static void requireActive(KafkaPrincipalEntity entity) {
        if (entity.getStatus() != KafkaPrincipalStatus.ACTIVE) {
            throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN,
                    "ACTIVE 상태 Kafka principal Secret만 조회할 수 있습니다");
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

    private static List<String> secretKeys(Secret secret) {
        if (secret.getData() == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(secret.getData().keySet());
        Collections.sort(keys);
        return keys;
    }

    private static String firstValue(Secret secret, String... keys) {
        for (String key : keys) {
            String value = secretValue(secret, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String secretValue(Secret secret, String key) {
        if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
            return secret.getStringData().get(key);
        }
        if (secret.getData() == null || !secret.getData().containsKey(key)) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
