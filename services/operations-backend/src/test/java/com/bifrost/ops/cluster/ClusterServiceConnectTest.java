package com.bifrost.ops.cluster;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.cluster.dto.ConnectClusterResponse;
import com.bifrost.ops.cluster.dto.ConnectClusterResponse.Plugin;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
@ExtendWith(MockitoExtension.class)
class ClusterServiceConnectTest {

    KubernetesClient client;

    @Mock private AdminClient admin;
    @Mock private PrometheusClient prometheus;
    @Mock private KafkaMetricsQuery kafkaMetricsQuery;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private PipelineRepository pipelineRepository;

    private static final String NS = "platform-kafka";
    private static final String CONNECT_CLUSTER = "platform-connect";

    @BeforeEach
    void registerKafkaConnectCrd() {
        client.apiextensions().v1().customResourceDefinitions().resource(
                new CustomResourceDefinitionBuilder()
                        .withNewMetadata().withName("kafkaconnects.kafka.strimzi.io").endMetadata()
                        .withNewSpec()
                            .withGroup("kafka.strimzi.io")
                            .withScope("Namespaced")
                            .withNewNames()
                                .withPlural("kafkaconnects")
                                .withSingular("kafkaconnect")
                                .withKind("KafkaConnect")
                            .endNames()
                            .addNewVersion().withName("v1").withServed(true).withStorage(true)
                                .withNewSchema().withNewOpenAPIV3Schema().withType("object")
                                .endOpenAPIV3Schema().endSchema()
                            .endVersion()
                        .endSpec()
                        .build()
        ).create();
    }

    @Test
    void connectUsesKafkaConnectStatusPluginCatalogBeforeConnectorClassFallback() {
        seedKafkaConnect(List.of(
                Map.of("class", "io.debezium.connector.postgresql.PostgresConnector",
                        "type", "source", "version", "3.5.1.Final"),
                Map.of("class", "io.debezium.connector.mysql.MySqlConnector",
                        "type", "source", "version", "3.5.1.Final"),
                Map.of("class", "io.confluent.connect.jdbc.JdbcSinkConnector",
                        "type", "sink", "version", "10.9.4")
        ));
        UUID pipelineId = UUID.randomUUID();
        when(connectorRepository.findAll()).thenReturn(List.of(
                connector(pipelineId, "orders-source",
                        "io.debezium.connector.postgresql.PostgresConnector", ConnectorKind.SOURCE),
                connector(pipelineId, "orders-sink",
                        "io.confluent.connect.jdbc.JdbcSinkConnector", ConnectorKind.SINK)
        ));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.empty());

        ConnectClusterResponse response = service("").connect();

        assertThat(response.connectors()).hasSize(2);
        assertThat(response.plugins()).hasSize(3);
        assertThat(response.plugins())
                .extracting(Plugin::className)
                .containsExactly(
                        "io.debezium.connector.postgresql.PostgresConnector",
                        "io.debezium.connector.mysql.MySqlConnector",
                        "io.confluent.connect.jdbc.JdbcSinkConnector");
    }

    private ClusterService service(String connectRestUrl) {
        return new ClusterService(admin, prometheus, kafkaMetricsQuery, client,
                connectorRepository, pipelineRepository, NS, CONNECT_CLUSTER, connectRestUrl);
    }

    private void seedKafkaConnect(List<Map<String, String>> connectorPlugins) {
        GenericKubernetesResource cr = new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("KafkaConnect")
                .withNewMetadata().withName(CONNECT_CLUSTER).withNamespace(NS).endMetadata()
                .addToAdditionalProperties("spec", Map.of(
                        "version", "4.2.0",
                        "groupId", CONNECT_CLUSTER,
                        "config", Map.of("key.converter", "org.apache.kafka.connect.json.JsonConverter")))
                .addToAdditionalProperties("status", Map.of("connectorPlugins", connectorPlugins))
                .build();
        client.resource(cr).inNamespace(NS).create();
    }

    private static ConnectorEntity connector(UUID pipelineId, String crName,
                                             String connectorClass, ConnectorKind kind) {
        ConnectorEntity entity = new ConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipelineId);
        entity.setCrName(crName);
        entity.setConnectorClass(connectorClass);
        entity.setKind(kind);
        entity.setTasksMax(kind == ConnectorKind.SOURCE ? 1 : 3);
        return entity;
    }
}
