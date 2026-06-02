package com.platform.orchestrator.kafka.connector.poc;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #10 PoC 스모크 테스트: Fabric8 mock 서버 위에서 Strimzi {@code KafkaConnector} CRD에
 * create/list/get/delete가 동작하는지 확인한다(실제 클러스터 없이 CRD 접근 경로 검증).
 */
@EnableKubernetesMockClient(crud = true)
class KafkaConnectorProbeTest {

    KubernetesClient client;

    private static final String NS = "platform-kafka";
    private static final String CONNECT_CLUSTER = "platform-connect";

    @Test
    void appliesListsAndDeletesKafkaConnector() {
        KafkaConnectorProbe probe = new KafkaConnectorProbe(client, NS, CONNECT_CLUSTER);

        assertThat(probe.listConnectors()).isEmpty();

        ConnectorInfo applied = probe.applySampleSourceConnector("orders-source", "team2", "shop");

        assertThat(applied.name()).isEqualTo("orders-source");
        assertThat(applied.namespace()).isEqualTo(NS);
        assertThat(applied.connectCluster()).isEqualTo(CONNECT_CLUSTER);
        assertThat(applied.connectorClass())
                .isEqualTo("io.debezium.connector.postgresql.PostgresConnector");
        assertThat(applied.tasksMax()).isEqualTo(1);
        assertThat(applied.state()).isEqualTo("UNKNOWN");

        List<ConnectorInfo> all = probe.listConnectors();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).name()).isEqualTo("orders-source");

        ConnectorInfo fetched = probe.getConnector("orders-source");
        assertThat(fetched).isNotNull();
        assertThat(fetched.connectorClass())
                .isEqualTo("io.debezium.connector.postgresql.PostgresConnector");

        assertThat(probe.deleteConnector("orders-source")).isTrue();
        assertThat(probe.listConnectors()).isEmpty();
    }

    @Test
    void applyIsIdempotent() {
        KafkaConnectorProbe probe = new KafkaConnectorProbe(client, NS, CONNECT_CLUSTER);

        probe.applySampleSourceConnector("dup-source", "team2", "shop");
        probe.applySampleSourceConnector("dup-source", "team2", "shop");

        assertThat(probe.listConnectors())
                .extracting(ConnectorInfo::name)
                .containsOnlyOnce("dup-source");
    }
}
