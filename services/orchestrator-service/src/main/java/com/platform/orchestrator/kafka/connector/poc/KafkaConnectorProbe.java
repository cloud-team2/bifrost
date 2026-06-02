package com.platform.orchestrator.kafka.connector.poc;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 화요일 PoC: Fabric8 + Strimzi typed API로 {@code KafkaConnector} CRD에 접근할 수 있는지 확인한다.
 *
 * <p>이 컴포넌트는 파이프라인 생성 로직(real provisioner)을 구현하기 전에,
 * KubernetesClient가 KafkaConnector CRD를 실제로 읽고/쓸 수 있는지를 검증하기 위한 최소 도구다.
 * 운영 흐름이 아니라 PoC/스모크 용도이며, 실제 provisioner는 #12에서 구현한다.
 *
 * <p>제공 동작:
 * <ul>
 *   <li>{@link #listConnectors()} — 네임스페이스의 KafkaConnector CR 목록 조회</li>
 *   <li>{@link #getConnector(String)} — 단일 CR 조회</li>
 *   <li>{@link #applySampleSourceConnector(String, String, String)} — 샘플 Debezium Source CR apply</li>
 *   <li>{@link #deleteConnector(String)} — CR 삭제</li>
 * </ul>
 */
@Component
public class KafkaConnectorProbe {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnectorProbe.class);

    private static final String CLUSTER_LABEL = "strimzi.io/cluster";

    private final KubernetesClient k8s;
    private final String namespace;
    private final String connectCluster;

    public KafkaConnectorProbe(KubernetesClient k8s,
                               @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
                               @Value("${kafka-connect.cluster:platform-connect}") String connectCluster) {
        this.k8s = k8s;
        this.namespace = namespace;
        this.connectCluster = connectCluster;
    }

    /** 네임스페이스 내 모든 KafkaConnector CR을 조회한다. */
    public List<ConnectorInfo> listConnectors() {
        List<KafkaConnector> items = k8s.resources(KafkaConnector.class)
                .inNamespace(namespace)
                .list()
                .getItems();
        log.info("KafkaConnector 목록 조회: namespace={}, count={}", namespace, items.size());
        return items.stream().map(this::toInfo).toList();
    }

    /** 단일 KafkaConnector CR을 조회한다. 없으면 {@code null}. */
    public ConnectorInfo getConnector(String name) {
        KafkaConnector cr = k8s.resources(KafkaConnector.class)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return cr == null ? null : toInfo(cr);
    }

    /**
     * 샘플 Debezium PostgreSQL Source KafkaConnector CR을 apply(create-or-update, 멱등)한다.
     *
     * <p>설계 토픽 네이밍 규칙을 따른다: {@code topic.prefix = cdc.table.{projectKey}.{dbName}}.
     * 자격증명(password)은 PoC라 주입하지 않는다 — real 구현(#12)에서 SecretStore.resolve 결과를 사용한다.
     *
     * @param name       CR 이름(예: {@code <pipelineId>-source})
     * @param projectKey 워크스페이스 슬러그(토픽 prefix·ACL 기준)
     * @param dbName     소스 DB 이름
     */
    public ConnectorInfo applySampleSourceConnector(String name, String projectKey, String dbName) {
        KafkaConnector cr = new KafkaConnectorBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(CLUSTER_LABEL, connectCluster)
                .endMetadata()
                .withNewSpec()
                    .withClassName("io.debezium.connector.postgresql.PostgresConnector")
                    .withTasksMax(1)
                    .addToConfig("plugin.name", "pgoutput")
                    .addToConfig("topic.prefix", "cdc.table." + projectKey + "." + dbName)
                    .addToConfig("topic.creation.default.partitions", 6)
                    .addToConfig("topic.creation.default.replication.factor", 3)
                .endSpec()
                .build();

        KafkaConnector applied = k8s.resource(cr)
                .inNamespace(namespace)
                .createOr(NonDeletingOperation::update);
        log.info("샘플 KafkaConnector apply 완료: namespace={}, name={}", namespace, name);
        return toInfo(applied);
    }

    /** KafkaConnector CR을 삭제한다. 삭제 요청이 접수되면 {@code true}. */
    public boolean deleteConnector(String name) {
        var deleted = k8s.resources(KafkaConnector.class)
                .inNamespace(namespace)
                .withName(name)
                .delete();
        boolean ok = deleted != null && !deleted.isEmpty();
        log.info("KafkaConnector 삭제 요청: namespace={}, name={}, accepted={}", namespace, name, ok);
        return ok;
    }

    private ConnectorInfo toInfo(KafkaConnector cr) {
        String connectorClass = cr.getSpec() != null ? cr.getSpec().getClassName() : null;
        Integer tasksMax = cr.getSpec() != null ? cr.getSpec().getTasksMax() : null;
        String cluster = cr.getMetadata() != null && cr.getMetadata().getLabels() != null
                ? cr.getMetadata().getLabels().get(CLUSTER_LABEL)
                : null;
        return new ConnectorInfo(
                cr.getMetadata() != null ? cr.getMetadata().getName() : null,
                cr.getMetadata() != null ? cr.getMetadata().getNamespace() : null,
                cluster,
                connectorClass,
                tasksMax,
                readConnectorState(cr),
                readTopics(cr)
        );
    }

    /** {@code status.connectorStatus.connector.state}를 안전하게 추출한다. */
    private String readConnectorState(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getConnectorStatus() == null) {
            return "UNKNOWN";
        }
        Object connector = status.getConnectorStatus().get("connector");
        if (connector instanceof Map<?, ?> connectorMap) {
            Object state = connectorMap.get("state");
            if (state != null) {
                return state.toString();
            }
        }
        return "UNKNOWN";
    }

    private List<String> readTopics(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getTopics() == null) {
            return List.of();
        }
        return status.getTopics();
    }
}
