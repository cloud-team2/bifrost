package com.bifrost.ops.pipeline.runtime;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.dto.TableMappingResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
@ExtendWith(MockitoExtension.class)
class PipelineRuntimeMetadataServiceTest {

    KubernetesClient client;

    @Mock private PipelineRepository pipelineRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private WorkspaceAccessGuard accessGuard;

    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-kafka";

    private final UUID wsId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

    @BeforeEach
    void registerStrimziCrds() {
        registerCrd("kafkas.kafka.strimzi.io", "Kafka", "kafkas");
        registerCrd("kafkaconnectors.kafka.strimzi.io", "KafkaConnector", "kafkaconnectors");
    }

    private PipelineRuntimeMetadataService service() {
        return new PipelineRuntimeMetadataService(pipelineRepository, workspaceRepository, connectorRepository,
                accessGuard, client, NS, CLUSTER, "localhost:9092", "http://connect:8083");
    }

    @Test
    void connectionGuideUsesKafkaCrBootstrapAndDoesNotExposeSecretValues() {
        seedKafkaCr("platform-kafka-kafka-bootstrap.platform-kafka.svc:9094");
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName("proj-team-a-user").withNamespace(NS).endMetadata()
                .addToData("sasl.username", b64("proj-team-a-user"))
                .addToData("sasl.password", b64("super-secret-password"))
                .build()).create();
        stubWorkspace();
        PipelineEntity pipeline = pipeline();
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));

        var out = service().connectionGuide(wsId, principal, pipelineId);

        verify(accessGuard).requireAccess(wsId, principal);
        assertThat(out.bootstrapServers()).isEqualTo("platform-kafka-kafka-bootstrap.platform-kafka.svc:9094");
        assertThat(out.authenticationMethod()).isEqualTo("SCRAM-SHA-512");
        assertThat(out.credentialReference().secretName()).isEqualTo("proj-team-a-user");
        assertThat(out.credentialReference().availableKeys()).contains("sasl.password");
        // raw secret은 스키마에 아예 없음(rawSecretIncluded 필드 제거) — toString에도 미노출 보장
        assertThat(out.authenticationTemplates()).allSatisfy(t -> assertThat(t.properties()).doesNotContainValue("super-secret-password"));
        assertThat(out.toString()).doesNotContain("super-secret-password");
    }

    @Test
    void tableMappingExtractsSourceTopicSinkFromKafkaConnectorConfig() {
        PipelineEntity pipeline = pipeline();
        pipeline.setSinkConnectorName(pipelineId + "-sink");
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));
        seedKafkaConnector(pipelineId + "-source", Map.of(
                "connector.class", "io.debezium.connector.postgresql.PostgresConnector",
                "topic.prefix", "cdc.table.team-a.shop-db123456",
                "table.include.list", "public.orders",
                "database.password", "must-not-leak"));
        seedKafkaConnector(pipelineId + "-sink", Map.of(
                "connector.class", "io.confluent.connect.jdbc.JdbcSinkConnector",
                "topics", "cdc.table.team-a.shop-db123456.public.orders",
                "transforms", "unwrap,route",
                "transforms.route.replacement", "$1",
                "connection.password", "also-secret"));

        TableMappingResponse out = service().tableMapping(wsId, principal, pipelineId);

        assertThat(out.mappings()).hasSize(1);
        assertThat(out.mappings().get(0).sourceTable()).isEqualTo("public.orders");
        assertThat(out.mappings().get(0).kafkaTopic()).isEqualTo("cdc.table.team-a.shop-db123456.public.orders");
        assertThat(out.mappings().get(0).sinkTable()).isEqualTo("orders");
        assertThat(out.toString()).doesNotContain("must-not-leak", "also-secret");
    }

    @Test
    void tableMappingReturnsEmptyWhenConnectorConfigIsMissing() {
        PipelineEntity pipeline = pipeline();
        pipeline.setSinkConnectorName(pipelineId + "-sink");
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));

        TableMappingResponse out = service().tableMapping(wsId, principal, pipelineId);

        assertThat(out.mappings()).isEmpty();
    }

    private void registerCrd(String name, String kind, String plural) {
        client.apiextensions().v1().customResourceDefinitions().resource(
                new CustomResourceDefinitionBuilder()
                        .withNewMetadata().withName(name).endMetadata()
                        .withNewSpec()
                            .withGroup("kafka.strimzi.io")
                            .withScope("Namespaced")
                            .withNewNames().withPlural(plural).withSingular(plural.substring(0, plural.length() - 1)).withKind(kind).endNames()
                            .addNewVersion().withName("v1").withServed(true).withStorage(true)
                                .withNewSchema().withNewOpenAPIV3Schema().withType("object").endOpenAPIV3Schema().endSchema()
                            .endVersion()
                        .endSpec()
                        .build()
        ).create();
    }

    private void seedKafkaCr(String bootstrapServers) {
        GenericKubernetesResource kafka = new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("Kafka")
                .withNewMetadata().withName(CLUSTER).withNamespace(NS).endMetadata()
                .addToAdditionalProperties("spec", Map.of(
                        "kafka", Map.of("listeners", List.of(Map.of(
                                "name", "scram",
                                "port", 9094,
                                "type", "internal",
                                "tls", true,
                                "authentication", Map.of("type", "scram-sha-512"))))))
                .addToAdditionalProperties("status", Map.of(
                        "listeners", List.of(Map.of(
                                "name", "scram",
                                "bootstrapServers", bootstrapServers))))
                .build();
        client.resource(kafka).inNamespace(NS).create();
    }

    private void seedKafkaConnector(String name, Map<String, String> config) {
        GenericKubernetesResource connector = new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("KafkaConnector")
                .withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToAdditionalProperties("spec", Map.of("config", config))
                .build();
        client.resource(connector).inNamespace(NS).create();
    }

    private void stubWorkspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(wsId);
        workspace.setNamespace("team-a");
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
    }

    private PipelineEntity pipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setTenantId(wsId);
        pipeline.setName("orders");
        pipeline.setPattern(PipelinePattern.DIRECT);
        pipeline.setStatus(PipelineLifecycle.ACTIVE);
        pipeline.setSchemaName("public");
        pipeline.setTableName("orders");
        pipeline.setTopicName("cdc.table.team-a.shop-db123456.public.orders");
        pipeline.setSourceConnectorName(pipelineId + "-source");
        return pipeline;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
