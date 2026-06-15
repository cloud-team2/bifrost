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
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineSyncServiceTest {

    private static final int EXPECTED_COUNT_QUERY_TIMEOUT_SEC = 4;
    private static final long EXPECTED_COUNT_CONNECTION_TIMEOUT_MS = 2000L;

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

    @Test
    void cdcSyncStatusUsesBoundedCountQueries() throws Exception {
        UUID wsId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID sinkId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "test@example.com");

        PipelineEntity pipeline = directPipeline(pipelineId, sourceId, sinkId);
        DatasourceEntity source = datasource(sourceId, DbType.POSTGRESQL, "secret://source");
        DatasourceEntity sink = datasource(sinkId, DbType.MARIADB, "secret://sink");

        CountQueryMock sourceCount = wireCount(
                source, "source-pass", "SELECT COUNT(*) FROM \"public\".\"orders\"", 120L);
        CountQueryMock sinkCount = wireCount(
                sink, "sink-pass", "SELECT COUNT(*) FROM `orders`", 100L);
        wireSyncStatusInputs(wsId, pipelineId, principal, pipeline, source, sink);

        SyncStatusResponse result = syncService.syncStatus(wsId, principal, pipelineId);

        assertThat(result.sourceRows()).isEqualTo(120L);
        assertThat(result.sinkRows()).isEqualTo(100L);
        assertThat(result.delta()).isEqualTo(20L);
        verify(dataSourceFactory).create(source, "source-pass", true, EXPECTED_COUNT_CONNECTION_TIMEOUT_MS);
        verify(dataSourceFactory).create(sink, "sink-pass", true, EXPECTED_COUNT_CONNECTION_TIMEOUT_MS);
        verifyQueryTimeoutBeforeExecution(sourceCount);
        verifyQueryTimeoutBeforeExecution(sinkCount);
    }

    @Test
    void cdcSyncStatusKeepsPartialRowsWhenCountQueryTimesOut() throws Exception {
        UUID wsId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID sinkId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "test@example.com");

        PipelineEntity pipeline = directPipeline(pipelineId, sourceId, sinkId);
        DatasourceEntity source = datasource(sourceId, DbType.POSTGRESQL, "secret://source");
        DatasourceEntity sink = datasource(sinkId, DbType.MARIADB, "secret://sink");

        CountQueryMock sourceCount = wireCountFailure(
                source, "source-pass", "SELECT COUNT(*) FROM \"public\".\"orders\"",
                new SQLTimeoutException("timeout"));
        CountQueryMock sinkCount = wireCount(
                sink, "sink-pass", "SELECT COUNT(*) FROM `orders`", 100L);
        wireSyncStatusInputs(wsId, pipelineId, principal, pipeline, source, sink);

        SyncStatusResponse result = syncService.syncStatus(wsId, principal, pipelineId);

        assertThat(result.sourceRows()).isEqualTo(-1L);
        assertThat(result.sinkRows()).isEqualTo(100L);
        assertThat(result.delta()).isEqualTo(-1L);
        verifyQueryTimeoutBeforeExecution(sourceCount);
        verifyQueryTimeoutBeforeExecution(sinkCount);
    }

    @Test
    void cdcSyncStatusKeepsSourceRowsWhenSinkConnectionTimesOut() throws Exception {
        UUID wsId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID sinkId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "test@example.com");

        PipelineEntity pipeline = directPipeline(pipelineId, sourceId, sinkId);
        DatasourceEntity source = datasource(sourceId, DbType.POSTGRESQL, "secret://source");
        DatasourceEntity sink = datasource(sinkId, DbType.MARIADB, "secret://sink");

        CountQueryMock sourceCount = wireCount(
                source, "source-pass", "SELECT COUNT(*) FROM \"public\".\"orders\"", 120L);
        HikariDataSource sinkPool = wireConnectionFailure(
                sink, "sink-pass", new SQLTimeoutException("connection timeout"));
        wireSyncStatusInputs(wsId, pipelineId, principal, pipeline, source, sink);

        SyncStatusResponse result = syncService.syncStatus(wsId, principal, pipelineId);

        assertThat(result.sourceRows()).isEqualTo(120L);
        assertThat(result.sinkRows()).isEqualTo(-1L);
        assertThat(result.delta()).isEqualTo(-1L);
        verify(dataSourceFactory).create(sink, "sink-pass", true, EXPECTED_COUNT_CONNECTION_TIMEOUT_MS);
        verify(sinkPool).close();
        verifyQueryTimeoutBeforeExecution(sourceCount);
    }

    private void wireSyncStatusInputs(UUID wsId,
                                      UUID pipelineId,
                                      AuthenticatedUser principal,
                                      PipelineEntity pipeline,
                                      DatasourceEntity source,
                                      DatasourceEntity sink) {
        doNothing().when(accessGuard).requireAccess(wsId, principal);
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));
        when(datasourceRepository.findByIdAndTenantId(pipeline.getSourceDatasourceId(), wsId))
                .thenReturn(Optional.of(source));
        when(datasourceRepository.findByIdAndTenantId(pipeline.getSinkDatasourceId(), wsId))
                .thenReturn(Optional.of(sink));
        when(secretStore.resolve("secret://source")).thenReturn(new DbCredential("source-user", "source-pass"));
        when(secretStore.resolve("secret://sink")).thenReturn(new DbCredential("sink-user", "sink-pass"));
        when(connectorRepository.findByPipelineId(pipelineId)).thenReturn(List.of());
    }

    private CountQueryMock wireCount(DatasourceEntity ds, String password, String sql, long rows) throws Exception {
        CountQueryMock count = wireCountStatement(ds, password);
        when(count.statement().executeQuery(sql)).thenReturn(count.resultSet());
        when(count.resultSet().next()).thenReturn(true);
        when(count.resultSet().getLong(1)).thenReturn(rows);
        return new CountQueryMock(count.statement(), count.resultSet(), sql);
    }

    private CountQueryMock wireCountFailure(DatasourceEntity ds, String password, String sql, Exception failure)
            throws Exception {
        CountQueryMock count = wireCountStatement(ds, password);
        when(count.statement().executeQuery(sql)).thenThrow(failure);
        return new CountQueryMock(count.statement(), count.resultSet(), sql);
    }

    private CountQueryMock wireCountStatement(DatasourceEntity ds, String password) throws Exception {
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSourceFactory.create(eq(ds), eq(password), eq(true), eq(EXPECTED_COUNT_CONNECTION_TIMEOUT_MS)))
                .thenReturn(pool);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        return new CountQueryMock(statement, resultSet, null);
    }

    private HikariDataSource wireConnectionFailure(DatasourceEntity ds, String password, Exception failure)
            throws Exception {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(dataSourceFactory.create(eq(ds), eq(password), eq(true), eq(EXPECTED_COUNT_CONNECTION_TIMEOUT_MS)))
                .thenReturn(pool);
        when(pool.getConnection()).thenThrow(failure);
        return pool;
    }

    private static void verifyQueryTimeoutBeforeExecution(CountQueryMock count) throws Exception {
        InOrder order = inOrder(count.statement());
        order.verify(count.statement()).setQueryTimeout(EXPECTED_COUNT_QUERY_TIMEOUT_SEC);
        order.verify(count.statement()).executeQuery(count.sql());
    }

    private static PipelineEntity directPipeline(UUID pipelineId, UUID sourceId, UUID sinkId) {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setPattern(PipelinePattern.DIRECT);
        pipeline.setSourceDatasourceId(sourceId);
        pipeline.setSinkDatasourceId(sinkId);
        pipeline.setSchemaName("public");
        pipeline.setTableName("orders");
        pipeline.setTopicName("cdc.table.team.shop.public.orders");
        return pipeline;
    }

    private static DatasourceEntity datasource(UUID id, DbType dbType, String secretRef) {
        DatasourceEntity ds = new DatasourceEntity();
        ds.setId(id);
        ds.setDbType(dbType);
        ds.setHost("localhost");
        ds.setPort(dbType == DbType.POSTGRESQL ? 5432 : 3306);
        ds.setDbName("app");
        ds.setUsername("app");
        ds.setSecretRef(secretRef);
        return ds;
    }

    private record CountQueryMock(Statement statement, ResultSet resultSet, String sql) {}
}
