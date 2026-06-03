package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.ProvisionErrorCode;
import com.bifrost.ops.provisioning.dto.ProvisionStage;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.secret.SecretStoreException;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * real provisioner의 성공/부분 실패 계약 검증(#12 골격 + #15 실패 처리).
 *
 * <p>성공 경로는 Fabric8 mock 서버(CRUD)로, 단계별 실패는 단계별로 예외를 주입해
 * {@link PipelineProvisionResult}의 {@code stage}/{@code errorCode}가 정확히 구분되는지 본다.
 */
@EnableKubernetesMockClient(crud = true)
class StrimziKafkaPipelineProvisionerTest {

    KubernetesClient client;

    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-connect";

    private final SourceDebeziumConnectorMapper sourceMapper = new SourceDebeziumConnectorMapper();
    private final JdbcSinkConnectorMapper sinkMapper = new JdbcSinkConnectorMapper();

    private PipelineProvisionCommand.Endpoint pgEndpoint(String table) {
        return new PipelineProvisionCommand.Endpoint(
                DbType.POSTGRESQL, "db-host", 5432, "shop", "public", table, "secret://src");
    }

    private PipelineProvisionCommand edaCommand() {
        return new PipelineProvisionCommand(
                UUID.randomUUID(), "team2", PipelinePattern.FAN_OUT, pgEndpoint("orders"), null);
    }

    private PipelineProvisionCommand cdcCommand() {
        PipelineProvisionCommand.Endpoint sink = new PipelineProvisionCommand.Endpoint(
                DbType.POSTGRESQL, "sink-host", 5432, "warehouse", null, null, "secret://sink");
        return new PipelineProvisionCommand(
                UUID.randomUUID(), "team2", PipelinePattern.DIRECT, pgEndpoint("orders"), sink);
    }

    private SecretStore resolvingStore() {
        SecretStore store = mock(SecretStore.class);
        when(store.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new DbCredential("dbuser", "dbpass"));
        return store;
    }

    private StrimziKafkaPipelineProvisioner provisioner(SecretStore store,
                                                        SourceDebeziumConnectorMapper src,
                                                        JdbcSinkConnectorMapper sink) {
        ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
        when(connectorRepository.findByCrName(any())).thenReturn(Optional.empty());
        when(connectorRepository.save(any(ConnectorEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new StrimziKafkaPipelineProvisioner(
                client, store, src, sink, connectorRepository, NS, CLUSTER);
    }

    @Test
    void edaCreatesSingleSourceConnector() {
        PipelineProvisionResult result = provisioner(resolvingStore(), sourceMapper, sinkMapper)
                .createPipelineResources(edaCommand());

        assertThat(result.success()).isTrue();
        assertThat(result.stage()).isEqualTo(ProvisionStage.COMPLETED);
        assertThat(result.connectors()).hasSize(1);
        assertThat(result.connectors().get(0).name()).endsWith("-source");
    }

    @Test
    void cdcCreatesSourceAndSinkConnectors() {
        PipelineProvisionResult result = provisioner(resolvingStore(), sourceMapper, sinkMapper)
                .createPipelineResources(cdcCommand());

        assertThat(result.success()).isTrue();
        assertThat(result.connectors()).hasSize(2);
        assertThat(result.connectors())
                .extracting(c -> c.name())
                .anySatisfy(n -> assertThat(n).endsWith("-source"))
                .anySatisfy(n -> assertThat(n).endsWith("-sink"));
    }

    @Test
    void secretResolveFailureReportsSecretStage() {
        SecretStore failing = mock(SecretStore.class);
        when(failing.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(SecretStoreException.notFound("secret://src"));

        PipelineProvisionResult result = provisioner(failing, sourceMapper, sinkMapper)
                .createPipelineResources(edaCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.stage()).isEqualTo(ProvisionStage.SECRET);
        assertThat(result.errorCode()).isEqualTo(ProvisionErrorCode.SECRET_RESOLVE_FAILED.code());
        assertThat(result.connectors()).isEmpty();
    }

    @Test
    void sourceConnectorApplyFailureReportsSourceStage() {
        SourceDebeziumConnectorMapper throwingSource = mock(SourceDebeziumConnectorMapper.class);
        when(throwingSource.map(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("apply boom"));

        PipelineProvisionResult result = provisioner(resolvingStore(), throwingSource, sinkMapper)
                .createPipelineResources(edaCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.stage()).isEqualTo(ProvisionStage.SOURCE_CONNECTOR);
        assertThat(result.errorCode()).isEqualTo(ProvisionErrorCode.SOURCE_CONNECTOR_FAILED.code());
        assertThat(result.connectors()).isEmpty();
    }

    @Test
    void sinkConnectorApplyFailureKeepsSourceAndReportsSinkStage() {
        JdbcSinkConnectorMapper throwingSink = mock(JdbcSinkConnectorMapper.class);
        when(throwingSink.map(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("sink boom"));

        PipelineProvisionResult result = provisioner(resolvingStore(), sourceMapper, throwingSink)
                .createPipelineResources(cdcCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.stage()).isEqualTo(ProvisionStage.SINK_CONNECTOR);
        assertThat(result.errorCode()).isEqualTo(ProvisionErrorCode.SINK_CONNECTOR_FAILED.code());
        // source는 이미 apply됐으므로 created 목록에 남아 cleanup 추적이 가능해야 한다
        assertThat(result.connectors()).hasSize(1);
        assertThat(result.connectors().get(0).name()).endsWith("-source");
    }
}
