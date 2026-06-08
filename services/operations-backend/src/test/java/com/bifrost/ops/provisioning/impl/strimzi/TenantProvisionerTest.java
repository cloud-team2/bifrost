package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantProvisioner KafkaUser/SCRAM 프로비저닝 검증(#47, #92). Fabric8 mock 위에서 KafkaUser CR이
 * SCRAM-SHA-512 인증으로 생성되는지, 멱등(ALREADY_EXISTED)인지, deprovision 시 삭제되는지 확인한다.
 *
 * <p>KafkaUser는 {@code genericKubernetesResources(ResourceDefinitionContext)}(v1)로 다루므로
 * 조회도 동일 GVK로 한다(typed {@code KafkaUser.class}는 v1beta2를 내므로 v1 리소스를 못 찾는다).
 *
 * <p>TODO(#92): 토픽 prefix ACL(scenario 2.1 워크스페이스 토픽 격리)은 Kafka authorizer 활성화
 * (인프라) 전제다. authorizer 비활성에선 authorization 섹션을 제외하므로 현재 단언도 SCRAM 인증까지만
 * 본다. authorizer 활성 시 impl·test에 ACL을 함께 재도입한다.
 */
@EnableKubernetesMockClient(crud = true)
class TenantProvisionerTest {

    KubernetesClient client;

    private static final String KAFKA_NS = "platform-kafka";
    private static final String CLUSTER = "platform-kafka";

    private static final ResourceDefinitionContext KAFKA_USER = new ResourceDefinitionContext.Builder()
            .withGroup("kafka.strimzi.io").withVersion("v1").withKind("KafkaUser")
            .withPlural("kafkausers").withNamespaced(true).build();

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

    private GenericKubernetesResource kafkaUser(String name) {
        return client.genericKubernetesResources(KAFKA_USER)
                .inNamespace(KAFKA_NS).withName(name).get();
    }

    @SuppressWarnings("unchecked")
    private static String authType(GenericKubernetesResource user) {
        Map<String, Object> spec = (Map<String, Object>) user.getAdditionalProperties().get("spec");
        Map<String, Object> auth = (Map<String, Object>) spec.get("authentication");
        return (String) auth.get("type");
    }

    @Test
    void provisionsKafkaUserWithScram() {
        UUID tenantId = UUID.randomUUID();
        seedUserSecret("proj-team9-user");

        TenantProvisionResponse resp = provisioner().provision(request(tenantId, "team9"));

        assertThat(resp.status()).isEqualTo(TenantProvisionResponse.Status.PROVISIONED);
        assertThat(resp.kafkaUserName()).isEqualTo("proj-team9-user");
        assertThat(resp.kafkaUserSecretName()).isEqualTo("proj-team9-user");

        assertThat(client.namespaces().withName("team9").get()).isNotNull();

        GenericKubernetesResource user = kafkaUser("proj-team9-user");
        assertThat(user).isNotNull();
        assertThat(user.getMetadata().getLabels())
                .containsEntry("strimzi.io/cluster", CLUSTER)
                .containsEntry("bifrost.io/tenant-id", tenantId.toString());
        assertThat(authType(user)).isEqualTo("scram-sha-512");
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

        assertThat(kafkaUser("proj-team9-user")).isNull();
    }
}
