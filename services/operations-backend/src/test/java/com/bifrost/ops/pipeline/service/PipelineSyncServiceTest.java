package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.pipeline.dto.SyncStatusResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineSyncServiceTest {

    @Mock PipelineRepository pipelineRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock SecretStore secretStore;
    @Mock DynamicDataSourceFactory dataSourceFactory;
    @Mock WorkspaceAccessGuard accessGuard;
    @Mock AdminClient adminClient;
    @Mock ConnectorRepository connectorRepository;

    @InjectMocks PipelineSyncService syncService;

    @Test
    void edaSyncStatusReturnsNotApplicable() {
        UUID wsId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "test@example.com");

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setPattern(PipelinePattern.FAN_OUT);

        doNothing().when(accessGuard).requireAccess(any(), any());
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));

        SyncStatusResponse result = syncService.syncStatus(wsId, principal, pipelineId);

        assertThat(result.applicable()).isFalse();
        assertThat(result.sourceRows()).isEqualTo(-1);
        assertThat(result.sinkRows()).isEqualTo(-1);
        assertThat(result.delta()).isEqualTo(-1);
    }

    /** #501: sink 커넥터가 FAILED면 행수와 무관하게 sinkFailed=true로 반환(완료 아님 판정의 핵심). */
    @Test
    void cdcSyncStatusReflectsSinkConnectorFailure() {
        UUID wsId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "test@example.com");

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setPattern(PipelinePattern.DIRECT);
        pipeline.setSourceDatasourceId(UUID.randomUUID());
        pipeline.setSinkDatasourceId(UUID.randomUUID());
        pipeline.setSchemaName("public");
        pipeline.setTableName("orders");
        pipeline.setTopicName("cdc.table.team.shop.public.orders");

        DatasourceEntity ds = new DatasourceEntity();
        ds.setDbType(DbType.POSTGRESQL);
        ds.setSecretRef("secret://x");

        ConnectorEntity sinkConnector = new ConnectorEntity();
        sinkConnector.setKind(ConnectorKind.SINK);
        sinkConnector.setState("FAILED");

        doNothing().when(accessGuard).requireAccess(any(), any());
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));
        when(datasourceRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(ds));
        // 행수 조회는 dataSourceFactory가 null 반환 → -1(countRows가 catch). secretStore는 resolve만 필요.
        lenient().when(secretStore.resolve(any())).thenReturn(new DbCredential("u", "p"));
        when(connectorRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sinkConnector));
        // adminClient는 stub 안 함 → sinkLagAndEnd가 예외로 [-1,-1] 반환(lag 미상)

        SyncStatusResponse result = syncService.syncStatus(wsId, principal, pipelineId);

        assertThat(result.applicable()).isTrue();
        assertThat(result.sinkFailed()).isTrue();   // 행수와 무관하게 오류로 잡힘
        assertThat(result.lag()).isEqualTo(-1);      // Kafka 조회 불가 시 -1
    }
}
