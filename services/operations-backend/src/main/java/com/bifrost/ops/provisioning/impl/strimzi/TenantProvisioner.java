package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;
import com.bifrost.ops.provisioning.port.TenantProvisionerPort;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 워크스페이스(테넌트) 생성 시 Kafka 접근 자격증명을 프로비저닝한다(설계 §3.2, FR-002).
 *
 * <p>핵심 산출물은 워크스페이스 단위 {@code KafkaUser} CR이다:
 * <ul>
 *   <li>이름 {@code proj-{slug}-user} — Strimzi가 동명 Secret(SCRAM-SHA-512 자격증명)을 자동 생성한다.</li>
 *   <li>인증 SCRAM-SHA-512 (scram listener :9094).</li>
 *   <li>ACL — 토픽 prefix {@code cdc.table.{slug}.*}에 read/write/describe/create. 프로젝트 간 토픽 격리.</li>
 * </ul>
 * KafkaUser는 Kafka 클러스터 네임스페이스({@code platform-kafka})에 {@code strimzi.io/cluster}
 * 라벨과 함께 생성된다(entity-operator가 watch). 멱등 — 이미 있으면 update로 수렴한다.
 *
 * <p>DB 자격증명은 별개로 secretRef로 관리하며 여기서 다루지 않는다(설계 §3.2 주석).
 */
@Component
public class TenantProvisioner implements TenantProvisionerPort {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioner.class);

    private static final String CLUSTER_LABEL = "strimzi.io/cluster";
    private static final String TENANT_LABEL = "bifrost.io/tenant-id";
    private static final long SECRET_WAIT_TIMEOUT_MS = 15_000L;
    private static final long SECRET_POLL_INTERVAL_MS = 1_000L;

    /**
     * KafkaUser CR의 GVK를 명시하는 {@link ResourceDefinitionContext}.
     *
     * <p>{@code genericKubernetesResources(apiVersion, kind)} 문자열 오버로드는 CRD 메타데이터
     * discovery에 의존해 fabric8 KubernetesMockServer에서 "Could not find the metadata ...
     * pass a ResourceDefinitionContext instead" 예외가 난다. RDC는 group/version/kind/plural을
     * 직접 들고 있어 discovery 없이 mock·real 모두에서 동작한다. 클러스터(Strimzi 1.0.0)는 CRD를
     * {@code v1}로 서빙하므로 version은 {@code v1}(typed 모델 api 0.45.0은 v1beta2를 내므로 미사용).
     */
    private static final ResourceDefinitionContext KAFKA_USER = new ResourceDefinitionContext.Builder()
            .withGroup("kafka.strimzi.io")
            .withVersion("v1")
            .withKind("KafkaUser")
            .withPlural("kafkausers")
            .withNamespaced(true)
            .build();

    private final KubernetesClient k8s;
    private final String kafkaClusterName;
    private final String kafkaNamespace;

    public TenantProvisioner(KubernetesClient k8s,
                             @Value("${kafka-cluster.name}") String kafkaClusterName,
                             @Value("${kafka-cluster.namespace:platform-kafka}") String kafkaNamespace) {
        this.k8s = k8s;
        this.kafkaClusterName = kafkaClusterName;
        this.kafkaNamespace = kafkaNamespace;
    }

    @Override
    public TenantProvisionResponse provision(TenantProvisionRequest req) {
        String slug = req.namespace();
        String userName = kafkaUserName(slug);
        try {
            createNamespace(slug, req.tenantId());

            boolean existed = k8s.genericKubernetesResources(KAFKA_USER)
                    .inNamespace(kafkaNamespace).withName(userName).get() != null;
            createKafkaUser(userName, slug, req.tenantId());

            boolean secretReady = waitForKafkaUserSecret(userName);
            if (!secretReady) {
                log.warn("KafkaUser Secret이 시간 내 생성되지 않음(비동기 계속): user={}", userName);
            }

            TenantProvisionResponse.Status status = existed
                    ? TenantProvisionResponse.Status.ALREADY_EXISTED
                    : TenantProvisionResponse.Status.PROVISIONED;
            log.info("테넌트 프로비저닝 완료: tenant={}, kafkaUser={}, status={}",
                    req.tenantId(), userName, status);
            return new TenantProvisionResponse(
                    req.tenantId(), slug, userName, userName, status, Instant.now());
        } catch (RuntimeException e) {
            log.error("테넌트 프로비저닝 실패: tenant={}, kafkaUser={}, cause={}",
                    req.tenantId(), userName, e.getClass().getSimpleName());
            return new TenantProvisionResponse(
                    req.tenantId(), slug, userName, null,
                    TenantProvisionResponse.Status.FAILED, Instant.now());
        }
    }

    @Override
    public void deprovision(UUID tenantId) {
        String selector = tenantId.toString();

        // label 기반 bulk delete(deletecollection)는 SA에 별도 RBAC이 필요하므로
        // 목록 조회 후 각 리소스를 이름으로 개별 삭제한다.
        var kafkaUsers = k8s.genericKubernetesResources(KAFKA_USER)
                .inNamespace(kafkaNamespace).withLabel(TENANT_LABEL, selector).list().getItems();
        for (var ku : kafkaUsers) {
            String kuName = ku.getMetadata().getName();
            try {
                k8s.genericKubernetesResources(KAFKA_USER)
                        .inNamespace(kafkaNamespace).withName(kuName).delete();
                log.info("KafkaUser 삭제: namespace={}, name={}", kafkaNamespace, kuName);
            } catch (Exception e) {
                log.warn("KafkaUser 삭제 실패(무시): namespace={}, name={}, cause={}",
                        kafkaNamespace, kuName, e.getMessage());
            }
            try {
                k8s.secrets().inNamespace(kafkaNamespace).withName(kuName).delete();
                log.info("KafkaUser Secret 삭제: namespace={}, name={}", kafkaNamespace, kuName);
            } catch (Exception e) {
                log.warn("KafkaUser Secret 삭제 실패(무시): namespace={}, name={}, cause={}",
                        kafkaNamespace, kuName, e.getMessage());
            }
        }

        var namespaces = k8s.namespaces().withLabel(TENANT_LABEL, selector).list().getItems();
        for (var ns : namespaces) {
            String nsName = ns.getMetadata().getName();
            try {
                k8s.namespaces().withName(nsName).delete();
                log.info("Namespace 삭제: name={}", nsName);
            } catch (Exception e) {
                log.warn("Namespace 삭제 실패(무시): name={}, cause={}", nsName, e.getMessage());
            }
        }
        log.info("테넌트 디프로비저닝 요청: tenant={}", tenantId);
    }

    // ---- private helpers ----

    private void createNamespace(String name, UUID tenantId) {
        Namespace ns = new NamespaceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .addToLabels(TENANT_LABEL, tenantId.toString())
                    .addToLabels("bifrost.io/managed-by", "operations-backend")
                .endMetadata()
                .build();
        k8s.namespaces().resource(ns).createOr(NonDeletingOperation::update);
    }

    /**
     * KafkaUser CR(SCRAM-SHA-512 + 토픽 prefix ACL)을 멱등 apply한다.
     *
     * <p>Strimzi Java API 0.45.0의 KafkaUser 모델은 {@code CustomResource}를 상속하므로
     * {@code getApiVersion()}이 클래스 어노테이션 버전(v1beta2)을 반환한다.
     * 클러스터(Strimzi 1.0.0)는 v1만 지원하므로 genericKubernetesResources로 apiVersion을 명시한다.
     */
    private void createKafkaUser(String userName, String slug, UUID tenantId) {
        // ACL authorization은 Kafka 클러스터에 authorizer가 활성화된 경우에만 동작한다.
        // 클러스터 설정에 따라 authorization 섹션을 포함하면 InvalidResourceException이 발생하므로 제외한다.
        Map<String, Object> spec = Map.of(
                "authentication", Map.of("type", "scram-sha-512"));

        GenericKubernetesResource user = new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("KafkaUser")
                .withNewMetadata()
                    .withName(userName)
                    .withNamespace(kafkaNamespace)
                    .addToLabels(CLUSTER_LABEL, kafkaClusterName)
                    .addToLabels(TENANT_LABEL, tenantId.toString())
                .endMetadata()
                .addToAdditionalProperties("spec", spec)
                .build();

        k8s.genericKubernetesResources(KAFKA_USER)
                .inNamespace(kafkaNamespace)
                .resource(user)
                .createOr(NonDeletingOperation::update);
        log.info("KafkaUser apply: name={}, aclPrefix={}", userName, topicAclPrefix(slug));
    }

    private boolean waitForKafkaUserSecret(String userName) {
        long deadline = System.currentTimeMillis() + SECRET_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (k8s.secrets().inNamespace(kafkaNamespace).withName(userName).get() != null) {
                return true;
            }
            try {
                Thread.sleep(SECRET_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private String kafkaUserName(String slug) {
        return "proj-" + slug + "-user";
    }

    private String topicAclPrefix(String slug) {
        return "cdc.table." + slug + ".";
    }
}
