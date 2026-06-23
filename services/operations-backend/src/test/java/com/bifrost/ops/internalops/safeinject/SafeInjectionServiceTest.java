package com.bifrost.ops.internalops.safeinject;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class SafeInjectionServiceTest {

    KubernetesClient client;

    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-connect";

    private WorkspaceRepository workspaceRepository;
    private SafeInjectionService service;

    @BeforeEach
    void setUp() {
        registerKafkaConnectorCrd();
        workspaceRepository = mock(WorkspaceRepository.class);
        workspace("e2e-rca-test", UUID.fromString("8898903c-d5db-4a8c-9ff3-104632f4f70f"));
        service = new SafeInjectionService(
                client,
                workspaceRepository,
                NS,
                CLUSTER,
                "e2e-rca-test");
    }

    @Test
    void createSchemaConnectorUsesSafeLabelsAndConverterFault() {
        SafeInjectionCreateResult result = service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("run-001", "schema", null));

        GenericKubernetesResource cr = client.resource(connectorTemplate(result.connectorName()))
                .inNamespace(NS)
                .get();
        assertThat(cr).isNotNull();
        assertThat(cr.getMetadata().getLabels())
                .containsEntry(SafeInjectionService.LABEL_SAFE, "true")
                .containsEntry(SafeInjectionService.LABEL_RUN, result.labels().get(SafeInjectionService.LABEL_RUN))
                .containsEntry("strimzi.io/cluster", CLUSTER);

        Map<String, Object> spec = asMap(cr.getAdditionalProperties().get("spec"));
        Map<String, Object> config = asMap(spec.get("config"));
        assertThat(spec.get("class")).isEqualTo("org.apache.kafka.connect.file.FileStreamSinkConnector");
        assertThat(config.get("value.converter")).isEqualTo("com.bifrost.safeinject.NoSuchConverter");
        assertThat(result.pipelineId()).isNotNull();
        assertThat(result.datasourceId()).isNotNull();
        assertThat(result.metadataPersisted()).isFalse();
    }

    @Test
    void rejectsWorkspaceOutsideSafeAllowlist() {
        workspace("prod-team", UUID.randomUUID());

        assertThatThrownBy(() -> service.create("prod-team",
                new SafeInjectionCreateRequest("run-002", "auth", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("configured test workspaces");
    }

    @Test
    void rejectsPrefixMatchWhenWorkspaceIsNotExactlyAllowed() {
        workspace("e2e-rca-test-prod", UUID.randomUUID());

        assertThatThrownBy(() -> service.create("e2e-rca-test-prod",
                new SafeInjectionCreateRequest("run-prefix", "auth", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("configured test workspaces");
    }

    @Test
    void allowsWorkspaceOnlyWhenExactValueIsConfigured() {
        workspace("safeinject-prod", UUID.randomUUID());
        SafeInjectionService exactService = new SafeInjectionService(
                client,
                workspaceRepository,
                NS,
                CLUSTER,
                "safeinject-prod");

        SafeInjectionCreateResult result = exactService.create("safeinject-prod",
                new SafeInjectionCreateRequest("run-exact", "schema", null));

        assertThat(result.connectorName()).startsWith("safeinject-");
    }

    @Test
    void rejectsConnectorNameOverrideSoCleanupRemainsDeterministic() {
        assertThatThrownBy(() -> service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("run-003", "auth", "safeinject-custom")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("override is not supported");
    }

    @Test
    void rejectsExistingNonSafeKubernetesConnectorCollision() {
        String generated = service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("collision-seed", "auth", null)).connectorName();
        service.cleanup("e2e-rca-test", "collision-seed");
        client.resource(new GenericKubernetesResourceBuilder()
                        .withApiVersion("kafka.strimzi.io/v1")
                        .withKind("KafkaConnector")
                        .withNewMetadata()
                            .withName(generated)
                            .withNamespace(NS)
                            .addToLabels("strimzi.io/cluster", CLUSTER)
                        .endMetadata()
                        .addToAdditionalProperties("spec", Map.of("class", "example.NonSafe", "tasksMax", 1, "config", Map.of()))
                        .build())
                .inNamespace(NS)
                .create();

        assertThatThrownBy(() -> service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("collision-seed", "auth", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing resource");
    }

    @Test
    void rejectsExistingSafeConnectorInsteadOfUpdatingIt() {
        service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("same-run", "lag", null));

        assertThatThrownBy(() -> service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("same-run", "lag", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing resource");
    }

    @Test
    void cleanupDeletesSafeResourcesAndReturnsResidualZero() {
        SafeInjectionCreateResult result = service.create("e2e-rca-test",
                new SafeInjectionCreateRequest("run-clean", "sink-fail", null));

        SafeInjectionCleanupResult cleanup = service.cleanup("e2e-rca-test", "run-clean");

        assertThat(cleanup.deletedK8sConnectors()).isEqualTo(1);
        assertThat(cleanup.deletedMetadataRows()).isZero();
        assertThat(cleanup.residualCount()).isZero();
        assertThat(client.resource(connectorTemplate(result.connectorName())).inNamespace(NS).get()).isNull();
    }

    private void registerKafkaConnectorCrd() {
        client.apiextensions().v1().customResourceDefinitions().resource(
                new CustomResourceDefinitionBuilder()
                        .withNewMetadata().withName("kafkaconnectors.kafka.strimzi.io").endMetadata()
                        .withNewSpec()
                            .withGroup("kafka.strimzi.io")
                            .withScope("Namespaced")
                            .withNewNames()
                                .withPlural("kafkaconnectors")
                                .withSingular("kafkaconnector")
                                .withKind("KafkaConnector")
                            .endNames()
                            .addNewVersion().withName("v1").withServed(true).withStorage(true)
                                .withNewSchema().withNewOpenAPIV3Schema().withType("object")
                                .endOpenAPIV3Schema().endSchema()
                            .endVersion()
                        .endSpec()
                        .build()
        ).create();
    }

    private void workspace(String namespace, UUID id) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setName(namespace);
        workspace.setNamespace(namespace);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        when(workspaceRepository.findByNamespace(namespace)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspace));
    }

    private GenericKubernetesResource connectorTemplate(String name) {
        return new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("KafkaConnector")
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(NS)
                .endMetadata()
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
