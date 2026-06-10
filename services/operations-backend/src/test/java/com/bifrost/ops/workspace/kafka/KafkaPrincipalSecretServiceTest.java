package com.bifrost.ops.workspace.kafka;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
@ExtendWith(MockitoExtension.class)
class KafkaPrincipalSecretServiceTest {

    KubernetesClient client;

    @Mock private KafkaPrincipalRepository principalRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ProjectMemberRepository memberRepository;
    @Mock private WorkspaceAccessGuard accessGuard;
    @Mock private AuditService auditService;

    private static final String NS = "platform-kafka";

    private final UUID wsId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, wsId, "admin@bifrost.io");

    private KafkaPrincipalService service() {
        return new KafkaPrincipalService(principalRepository, workspaceRepository, memberRepository, accessGuard,
                client, auditService, NS);
    }

    @Test
    void secretMasksPasswordByDefaultAndAuditsLookup() {
        allowManager();
        WorkspaceEntity workspace = workspace("team-a");
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        KafkaPrincipalEntity principal = kafkaPrincipal("proj-team-a-user", KafkaPrincipalStatus.ACTIVE);
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId)).thenReturn(Optional.of(principal));
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName("proj-team-a-user").withNamespace(NS).endMetadata()
                .addToData("sasl.username", b64("proj-team-a-user"))
                .addToData("sasl.password", b64("very-secret"))
                .build()).create();

        var out = service().secret(wsId, user, principalId);

        assertThat(out.secretName()).isEqualTo("proj-team-a-user");
        assertThat(out.username()).isEqualTo("proj-team-a-user");
        assertThat(out.passwordMasked()).isEqualTo("********");
        assertThat(out.exposurePolicy()).isEqualTo("MASKED_REFERENCE_ONLY");
        assertThat(out.availableKeys()).contains("sasl.password");
        // 정책상 raw secret은 응답 어디에도(toString 포함) 노출되지 않아야 한다
        assertThat(out.toString()).doesNotContain("very-secret");
        verify(auditService).record(wsId, user.email(), "KAFKA_PRINCIPAL_SECRET_VIEW", "KAFKA_PRINCIPAL",
                principalId, "username=proj-team-a-user, secretName=proj-team-a-user");
    }

    @Test
    void secretNeverReturnsRawPasswordEvenWhenSecretHasOne() {
        allowManager();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace("team-a")));
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId))
                .thenReturn(Optional.of(kafkaPrincipal("proj-team-a-user", KafkaPrincipalStatus.ACTIVE)));
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName("proj-team-a-user").withNamespace(NS).endMetadata()
                .addToData("password", b64("raw-password"))
                .build()).create();

        var out = service().secret(wsId, user, principalId);

        // raw가 존재해도 마스킹/레퍼런스만 반환(정책: 원문 read deny)
        assertThat(out.passwordMasked()).isEqualTo("********");
        assertThat(out.exposurePolicy()).isEqualTo("MASKED_REFERENCE_ONLY");
        assertThat(out.toString()).doesNotContain("raw-password");
    }

    @Test
    void secretSecondaryUsernameAliasMismatchIsFailedClosed() {
        // sasl.username은 일치하지만 username alias가 다른 값으로 꼬인 경우도 fail-closed (모든 alias 검증)
        allowManager();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace("team-a")));
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId))
                .thenReturn(Optional.of(kafkaPrincipal("proj-team-a-user", KafkaPrincipalStatus.ACTIVE)));
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName("proj-team-a-user").withNamespace(NS).endMetadata()
                .addToData("sasl.username", b64("proj-team-a-user"))
                .addToData("username", b64("someone-else"))
                .addToData("sasl.password", b64("very-secret"))
                .build()).create();

        assertThatThrownBy(() -> service().secret(wsId, user, principalId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND));
    }

    @Test
    void inactivePrincipalSecretIsDenied() {
        allowManager();
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId))
                .thenReturn(Optional.of(kafkaPrincipal("proj-team-a-user", KafkaPrincipalStatus.INACTIVE)));

        assertThatThrownBy(() -> service().secret(wsId, user, principalId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
    }

    @Test
    void missingSecretReturnsPrincipalNotFoundCode() {
        allowManager();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace("team-a")));
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId))
                .thenReturn(Optional.of(kafkaPrincipal("proj-team-a-user", KafkaPrincipalStatus.ACTIVE)));

        assertThatThrownBy(() -> service().secret(wsId, user, principalId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND));
    }

    @Test
    void principalUsernameMustMatchTenantProvisionerSecretRule() {
        allowManager();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace("team-a")));
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId))
                .thenReturn(Optional.of(kafkaPrincipal("custom-user", KafkaPrincipalStatus.ACTIVE)));

        assertThatThrownBy(() -> service().secret(wsId, user, principalId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND));
    }

    @Test
    void secretContentUsernameMismatchIsFailedClosed() {
        // 이름 규칙(proj-team-a-user)은 맞지만 Secret 내부 sasl.username이 다른 principal로 꼬인 경우 — fail-closed
        allowManager();
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace("team-a")));
        when(principalRepository.findByIdAndWorkspaceId(principalId, wsId))
                .thenReturn(Optional.of(kafkaPrincipal("proj-team-a-user", KafkaPrincipalStatus.ACTIVE)));
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName("proj-team-a-user").withNamespace(NS).endMetadata()
                .addToData("sasl.username", b64("someone-else"))
                .addToData("sasl.password", b64("very-secret"))
                .build()).create();

        assertThatThrownBy(() -> service().secret(wsId, user, principalId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.KAFKA_PRINCIPAL_NOT_FOUND));
    }

    private void allowManager() {
        when(workspaceRepository.existsById(wsId)).thenReturn(true);
        when(memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(wsId, userId, List.of(Role.OWNER, Role.ADMIN)))
                .thenReturn(true);
    }

    private WorkspaceEntity workspace(String namespace) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(wsId);
        workspace.setNamespace(namespace);
        return workspace;
    }

    private KafkaPrincipalEntity kafkaPrincipal(String username, KafkaPrincipalStatus status) {
        KafkaPrincipalEntity entity = new KafkaPrincipalEntity();
        entity.setId(principalId);
        entity.setWorkspaceId(wsId);
        entity.setUsername(username);
        entity.setStatus(status);
        return entity;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
