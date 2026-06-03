package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserAuthorizationSimple;
import io.strimzi.api.kafka.model.user.KafkaUserScramSha512ClientAuthentication;
import io.strimzi.api.kafka.model.user.acl.AclOperation;
import io.strimzi.api.kafka.model.user.acl.AclResourcePatternType;
import io.strimzi.api.kafka.model.user.acl.AclRule;
import io.strimzi.api.kafka.model.user.acl.AclRuleTopicResource;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantProvisioner KafkaUser/SCRAM 프로비저닝 검증(#47). Fabric8 mock 위에서 KafkaUser CR이
 * SCRAM-SHA-512 인증 + 토픽 prefix ACL로 생성되는지, 멱등(ALREADY_EXISTED)인지 확인한다.
 */
@EnableKubernetesMockClient(crud = true)
class TenantProvisionerTest {

    KubernetesClient client;

    private static final String KAFKA_NS = "platform-kafka";
    private static final String CLUSTER = "platform-kafka";

    private TenantProvisioner provisioner() {
        return new TenantProvisioner(client, CLUSTER, KAFKA_NS);
    }

    private TenantProvisionRequest request(UUID tenantId, String slug) {
        return new TenantProvisionRequest(
                tenantId, slug, TenantProvisionRequest.ResourceQuotaSpec.defaultQuota());
    }

    /** Strimzi가 비동기로 만드는 Secret을 미리 심어 wait가 즉시 통과하도록 한다. */
    private void seedUserSecret(String userName) {
        client.secrets().inNamespace(KAFKA_NS).resource(new SecretBuilder()
                .withNewMetadata().withName(userName).withNamespace(KAFKA_NS).endMetadata()
                .build()).create();
    }

    @Test
    void provisionsKafkaUserWithScramAndTopicPrefixAcl() {
        UUID tenantId = UUID.randomUUID();
        seedUserSecret("proj-team9-user");

        TenantProvisionResponse resp = provisioner().provision(request(tenantId, "team9"));

        assertThat(resp.status()).isEqualTo(TenantProvisionResponse.Status.PROVISIONED);
        assertThat(resp.kafkaUserName()).isEqualTo("proj-team9-user");
        assertThat(resp.kafkaUserSecretName()).isEqualTo("proj-team9-user");

        assertThat(client.namespaces().withName("team9").get()).isNotNull();

        KafkaUser user = client.resources(KafkaUser.class)
                .inNamespace(KAFKA_NS).withName("proj-team9-user").get();
        assertThat(user).isNotNull();
        assertThat(user.getMetadata().getLabels())
                .containsEntry("strimzi.io/cluster", CLUSTER)
                .containsEntry("bifrost.io/tenant-id", tenantId.toString());
        assertThat(user.getSpec().getAuthentication())
                .isInstanceOf(KafkaUserScramSha512ClientAuthentication.class);

        KafkaUserAuthorizationSimple authz =
                (KafkaUserAuthorizationSimple) user.getSpec().getAuthorization();
        assertThat(authz.getAcls()).hasSize(1);
        AclRule rule = authz.getAcls().get(0);
        AclRuleTopicResource resource = (AclRuleTopicResource) rule.getResource();
        assertThat(resource.getName()).isEqualTo("cdc.table.team9.");
        assertThat(resource.getPatternType()).isEqualTo(AclResourcePatternType.PREFIX);
        assertThat(rule.getOperations())
                .contains(AclOperation.READ, AclOperation.WRITE, AclOperation.DESCRIBE);
    }

    @Test
    void secondProvisionIsIdempotentAndReportsAlreadyExisted() {
        UUID tenantId = UUID.randomUUID();
        seedUserSecret("proj-team9-user");

        provisioner().provision(request(tenantId, "team9"));
        TenantProvisionResponse second = provisioner().provision(request(tenantId, "team9"));

        assertThat(second.status()).isEqualTo(TenantProvisionResponse.Status.ALREADY_EXISTED);
        assertThat(second.kafkaUserName()).isEqualTo("proj-team9-user");
    }

    @Test
    void deprovisionDeletesKafkaUser() {
        UUID tenantId = UUID.randomUUID();
        seedUserSecret("proj-team9-user");
        provisioner().provision(request(tenantId, "team9"));

        provisioner().deprovision(tenantId);

        assertThat(client.resources(KafkaUser.class)
                .inNamespace(KAFKA_NS).withName("proj-team9-user").get()).isNull();
    }
}
