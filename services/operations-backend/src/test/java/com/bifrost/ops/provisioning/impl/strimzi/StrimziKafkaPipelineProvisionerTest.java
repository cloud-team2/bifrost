package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.ProvisionStage;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretContext;
import com.bifrost.ops.secret.mock.InMemorySecretStore;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * real provisioner(#12) 스모크 테스트: Fabric8 mock 서버 위에서 EDA/CDC 생성 시
 * KafkaConnector CR이 실제로 apply되는지, 부분 실패가 result로 구분되는지 검증한다.
 */
@EnableKubernetesMockClient(crud = true)
class StrimziKafkaPipelineProvisionerTest {

    KubernetesClient client;

    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-connect";

    private StrimziKafkaPipelineProvisioner provisioner(InMemorySecretStore secretStore) {
        ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
        when(connectorRepository.findByCrName(any())).thenReturn(Optional.empty());
        when(connectorRepository.save(any(ConnectorEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new StrimziKafkaPipelineProvisioner(
                client, secretStore,
                new SourceDebeziumConnectorMapper(), new JdbcSinkConnectorMapper(),
                connectorRepository, NS, CLUSTER);
    }

    @Test
    void edaCreatesSingleSourceConnector() {
        InMemorySecretStore secrets = new InMemorySecretStore();
        UUID pipelineId = UUID.randomUUID();
        String srcRef = secrets.put(
                new SecretContext(UUID.randomUUID(), "shop-src"), new DbCredential("svc", "pw"));

        PipelineProvisionCommand command = new PipelineProvisionCommand(
                pipelineId, "team2", PipelinePattern.FAN_OUT,
                new PipelineProvisionCommand.Endpoint(
                        DbType.POSTGRESQL, "db.internal", 5432, "shop", "public", "orders", srcRef),
                null);

        PipelineProvisionResult result = provisioner(secrets).createPipelineResources(command);

        assertThat(result.success()).isTrue();
        assertThat(result.stage()).isEqualTo(ProvisionStage.COMPLETED);
        assertThat(result.connectors()).hasSize(1);
        assertThat(result.topicPrefix()).isEqualTo("cdc.table.team2.shop");

        KafkaConnector source = client.resources(KafkaConnector.class)
                .inNamespace(NS).withName(pipelineId + "-source").get();
        assertThat(source).isNotNull();
    }

    @Test
    void cdcCreatesSourceAndSinkConnectors() {
        InMemorySecretStore secrets = new InMemorySecretStore();
        UUID pipelineId = UUID.randomUUID();
        String srcRef = secrets.put(
                new SecretContext(UUID.randomUUID(), "shop-src"), new DbCredential("svc", "pw"));
        String sinkRef = secrets.put(
                new SecretContext(UUID.randomUUID(), "wh-sink"), new DbCredential("sinker", "pw"));

        PipelineProvisionCommand command = new PipelineProvisionCommand(
                pipelineId, "team2", PipelinePattern.DIRECT,
                new PipelineProvisionCommand.Endpoint(
                        DbType.POSTGRESQL, "src.internal", 5432, "shop", "public", "orders", srcRef),
                new PipelineProvisionCommand.Endpoint(
                        DbType.MARIADB, "sink.internal", 3306, "warehouse", null, null, sinkRef));

        PipelineProvisionResult result = provisioner(secrets).createPipelineResources(command);

        assertThat(result.success()).isTrue();
        assertThat(result.connectors()).hasSize(2);
        assertThat(client.resources(KafkaConnector.class).inNamespace(NS)
                .withName(pipelineId + "-source").get()).isNotNull();
        assertThat(client.resources(KafkaConnector.class).inNamespace(NS)
                .withName(pipelineId + "-sink").get()).isNotNull();
    }

    @Test
    void missingSecretFailsAtSecretStage() {
        InMemorySecretStore secrets = new InMemorySecretStore();
        UUID pipelineId = UUID.randomUUID();

        PipelineProvisionCommand command = new PipelineProvisionCommand(
                pipelineId, "team2", PipelinePattern.FAN_OUT,
                new PipelineProvisionCommand.Endpoint(
                        DbType.POSTGRESQL, "db.internal", 5432, "shop", "public", "orders", "does-not-exist"),
                null);

        PipelineProvisionResult result = provisioner(secrets).createPipelineResources(command);

        assertThat(result.success()).isFalse();
        assertThat(result.stage()).isEqualTo(ProvisionStage.SECRET);
        assertThat(result.connectors()).isEmpty();
    }
}
