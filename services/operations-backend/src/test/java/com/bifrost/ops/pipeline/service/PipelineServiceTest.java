package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.database.service.CdcReadinessService;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private DatasourceRepository datasourceRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private PipelineProvisioningService provisioningService;
    @Mock private WorkspaceAccessGuard accessGuard;
    @Mock private EventService eventService;
    @Mock private AuditService auditService;
    @Mock private com.bifrost.ops.pipeline.kafka.KafkaResourceCleaner kafkaResourceCleaner;
    @Mock private com.bifrost.ops.pipeline.PostgresReplicationSlotCleaner postgresSlotCleaner;
    @Mock private CdcReadinessService cdcReadinessService;
    @Mock private KafkaMetricsQuery kafkaMetricsQuery;

    private PipelineService service() {
        return new PipelineService(pipelineRepository, datasourceRepository, workspaceRepository,
                connectorRepository, provisioningService, accessGuard, eventService, auditService,
                kafkaResourceCleaner, cdcReadinessService, postgresSlotCleaner,
                org.mockito.Mockito.mock(com.bifrost.ops.incident.IncidentService.class),
                kafkaMetricsQuery);
    }

    /** create() 호출 시 live 점검이 OK를 반환하도록 기본 stub. */
    private void stubLiveCheckOk() {
        CdcReadinessResponse ok = new CdcReadinessResponse(CdcReadinessStatus.OK, List.of());
        lenient().when(cdcReadinessService.check(any(), any())).thenReturn(ok);
    }

    /** live 점검이 BLOCKED(slot 고갈)를 반환하도록 stub. */
    private void stubLiveCheckBlocked() {
        CdcReadinessResponse blocked = new CdcReadinessResponse(CdcReadinessStatus.BLOCKED,
                List.of(CdcReadinessResponse.CdcCheck.of("Replication Slot 여유", CdcReadinessStatus.BLOCKED,
                        "10/10", "< max_replication_slots", "사용하지 않는 slot을 정리하세요")));
        when(cdcReadinessService.check(any(), any())).thenReturn(blocked);
    }

    private final UUID wsId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID sinkId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

    // ---------- 생성: EDA ----------

    @Test
    void createsEdaPipelineReturnsCreatingAndProvisionsSourceOnly() {
        stubSource("OK");
        stubLiveCheckOk();
        stubWorkspace();
        when(pipelineRepository.existsByTenantIdAndName(wsId, "orders-eda")).thenReturn(false);
        when(pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                any(), any(), any(), any(), any())).thenReturn(false);
        when(pipelineRepository.saveAndFlush(any())).thenAnswer(this::withId);
        when(provisioningService.provision(any()))
                .thenAnswer(inv -> successResult(inv.getArgument(0), false));

        PipelineResponse resp = service().create(wsId, principal,
                new PipelineCreateRequest("orders-eda", "fan-out", sourceId, null, "public", "orders"));

        assertThat(resp.status()).isEqualTo("creating");
        assertThat(resp.pattern()).isEqualTo("fan-out");
        assertThat(resp.sinkDbId()).isNull();
        assertThat(resp.sourceConnector()).isNotNull();

        ArgumentCaptor<PipelineProvisionCommand> cmd = ArgumentCaptor.forClass(PipelineProvisionCommand.class);
        verify(provisioningService).provision(cmd.capture());
        assertThat(cmd.getValue().pattern()).isEqualTo(PipelinePattern.FAN_OUT);
        assertThat(cmd.getValue().sink()).isNull();
        assertThat(cmd.getValue().source().table()).isEqualTo("orders");
    }

    // ---------- 생성: CDC ----------

    @Test
    void createsCdcPipelineProvisionsSourceAndSink() {
        stubSource("OK");
        stubLiveCheckOk();
        stubSink();
        stubWorkspace();
        when(pipelineRepository.existsByTenantIdAndName(any(), any())).thenReturn(false);
        when(pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                any(), any(), any(), any(), any())).thenReturn(false);
        when(pipelineRepository.saveAndFlush(any())).thenAnswer(this::withId);
        when(provisioningService.provision(any()))
                .thenAnswer(inv -> successResult(inv.getArgument(0), true));

        PipelineResponse resp = service().create(wsId, principal,
                new PipelineCreateRequest("orders-cdc", "direct", sourceId, sinkId, "public", "orders"));

        assertThat(resp.status()).isEqualTo("creating");
        assertThat(resp.pattern()).isEqualTo("direct");
        assertThat(resp.sinkConnector()).isNotNull();

        ArgumentCaptor<PipelineProvisionCommand> cmd = ArgumentCaptor.forClass(PipelineProvisionCommand.class);
        verify(provisioningService).provision(cmd.capture());
        assertThat(cmd.getValue().pattern()).isEqualTo(PipelinePattern.DIRECT);
        assertThat(cmd.getValue().sink()).isNotNull();
    }

    // ---------- 검증 ----------

    @Test
    void rejectsFanOutWithSink() {
        assertValidationFailure(new PipelineCreateRequest("x", "fan-out", sourceId, sinkId, "public", "t"));
        verify(provisioningService, never()).provision(any());
    }

    @Test
    void rejectsDirectWithoutSink() {
        assertValidationFailure(new PipelineCreateRequest("x", "direct", sourceId, null, "public", "t"));
    }

    @Test
    void rejectsBlockedSource() {
        stubSource("BLOCKED");
        assertValidationFailure(new PipelineCreateRequest("x", "fan-out", sourceId, null, "public", "t"));
    }

    @Test
    void rejectsUnknownSource() {
        when(datasourceRepository.findByIdAndTenantId(sourceId, wsId)).thenReturn(Optional.empty());
        assertValidationFailure(new PipelineCreateRequest("x", "fan-out", sourceId, null, "public", "t"));
    }

    @Test
    void rejectsDuplicateName() {
        stubSource("OK");
        when(pipelineRepository.existsByTenantIdAndName(wsId, "dup")).thenReturn(true);
        assertValidationFailure(new PipelineCreateRequest("dup", "fan-out", sourceId, null, "public", "t"));
    }

    @Test
    void rejectsDuplicateSourceTablePattern() {
        stubSource("OK");
        when(pipelineRepository.existsByTenantIdAndName(any(), any())).thenReturn(false);
        when(pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                any(), any(), any(), any(), any())).thenReturn(true);
        assertValidationFailure(new PipelineCreateRequest("x", "fan-out", sourceId, null, "public", "orders"));
    }

    @Test
    void rejectsSourceWhenLiveCheckReturnsBlockedDueToSlotExhaustion() {
        stubSource("OK"); // 저장된 값은 OK지만 live 점검이 slot 고갈로 BLOCKED 반환
        stubLiveCheckBlocked();
        assertValidationFailure(new PipelineCreateRequest("x", "fan-out", sourceId, null, "public", "t"));
        verify(provisioningService, never()).provision(any());
    }

    // ---------- 목록 / 상세 / 생명주기 ----------

    @Test
    void listFiltersByStatus() {
        when(pipelineRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(wsId, PipelineLifecycle.ACTIVE))
                .thenReturn(List.of(entity(PipelineLifecycle.ACTIVE)));
        assertThat(service().list(wsId, principal, "active")).hasSize(1);
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().get(wsId, principal, id))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.PIPELINE_NOT_FOUND));
    }

    @Test
    void listConnectorsAttachesRuntimeMetricsWhenPrometheusEnabled() {
        UUID id = UUID.randomUUID();
        PipelineEntity p = entity(PipelineLifecycle.ACTIVE);
        p.setId(id);
        p.setTopicName("cdc.team.orders");

        ConnectorEntity connector = new ConnectorEntity();
        connector.setPipelineId(id);
        connector.setCrName("orders-source");
        connector.setKind(ConnectorKind.SOURCE);
        connector.setConnectorClass("io.debezium.connector.postgresql.PostgresConnector");
        connector.setState("RUNNING");
        connector.setTasksMax(1);

        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(id)).thenReturn(List.of(connector));
        when(kafkaMetricsQuery.isEnabled()).thenReturn(true);
        when(kafkaMetricsQuery.connectorErrorRatePct(ConnectorKind.SOURCE, "orders-source", "cdc.team.orders"))
                .thenReturn(0.75);
        when(kafkaMetricsQuery.connectorPollBatchAvg("orders-source", ConnectorKind.SOURCE)).thenReturn(42.5);
        when(kafkaMetricsQuery.connectorPollBatchMax("orders-source", ConnectorKind.SOURCE)).thenReturn(120.0);
        when(kafkaMetricsQuery.connectorRetriesTotal("orders-source")).thenReturn(3L);
        when(kafkaMetricsQuery.connectorRecordsPerSec(ConnectorKind.SOURCE, "orders-source", "cdc.team.orders"))
                .thenReturn(25.0);
        when(kafkaMetricsQuery.connectorRecordsSeries(org.mockito.ArgumentMatchers.eq(ConnectorKind.SOURCE),
                org.mockito.ArgumentMatchers.eq("orders-source"), org.mockito.ArgumentMatchers.eq("cdc.team.orders"),
                anyLong(), anyLong(), anyLong())).thenReturn(Map.of(100L, 25.0));

        List<ConnectorResponse> connectors = service().listConnectors(wsId, principal, id);

        assertThat(connectors).hasSize(1);
        ConnectorResponse out = connectors.get(0);
        assertThat(out.errorRatePct()).isEqualTo(0.75);
        assertThat(out.pollBatchAvg()).isEqualTo(42.5);
        assertThat(out.pollBatchMax()).isEqualTo(120.0);
        assertThat(out.retriesTotal()).isEqualTo(3L);
        assertThat(out.recordsPerSec()).isEqualTo(25.0);
        assertThat(out.metricsStatus()).isEqualTo("AVAILABLE");
        assertThat(out.metricsMessage()).isNull();
        assertThat(out.recordsPerSecSeries()).singleElement()
                .satisfies(point -> {
                    assertThat(point.timestamp()).isEqualTo(100_000L);
                    assertThat(point.value()).isEqualTo(25.0);
                });
    }

    @Test
    void listConnectorsMarksMetricsUnavailableWhenPrometheusQueryFails() {
        UUID id = UUID.randomUUID();
        PipelineEntity p = entity(PipelineLifecycle.ACTIVE);
        p.setId(id);
        p.setTopicName("cdc.team.orders");

        ConnectorEntity connector = new ConnectorEntity();
        connector.setPipelineId(id);
        connector.setCrName("orders-source");
        connector.setKind(ConnectorKind.SOURCE);
        connector.setConnectorClass("io.debezium.connector.postgresql.PostgresConnector");
        connector.setState("RUNNING");
        connector.setTasksMax(1);

        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(id)).thenReturn(List.of(connector));
        when(kafkaMetricsQuery.isEnabled()).thenReturn(true);
        when(kafkaMetricsQuery.connectorErrorRatePct(ConnectorKind.SOURCE, "orders-source", "cdc.team.orders"))
                .thenThrow(new RuntimeException("prometheus down"));

        List<ConnectorResponse> connectors = service().listConnectors(wsId, principal, id);

        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).metricsStatus()).isEqualTo("UNAVAILABLE");
        assertThat(connectors.get(0).metricsMessage()).isEqualTo("Prometheus 조회 실패");
        assertThat(connectors.get(0).recordsPerSecSeries()).isEmpty();
    }

    @Test
    void listConnectorsMarksMetricsUnavailableWhenAllPrometheusSeriesAreAbsent() {
        UUID id = UUID.randomUUID();
        PipelineEntity p = entity(PipelineLifecycle.ACTIVE);
        p.setId(id);
        p.setTopicName("cdc.team.orders");

        ConnectorEntity connector = new ConnectorEntity();
        connector.setPipelineId(id);
        connector.setCrName("orders-source");
        connector.setKind(ConnectorKind.SOURCE);
        connector.setConnectorClass("io.debezium.connector.postgresql.PostgresConnector");
        connector.setState("RUNNING");
        connector.setTasksMax(1);

        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(id)).thenReturn(List.of(connector));
        when(kafkaMetricsQuery.isEnabled()).thenReturn(true);
        when(kafkaMetricsQuery.connectorErrorRatePct(ConnectorKind.SOURCE, "orders-source", "cdc.team.orders"))
                .thenReturn(null);
        when(kafkaMetricsQuery.connectorPollBatchAvg("orders-source", ConnectorKind.SOURCE)).thenReturn(null);
        when(kafkaMetricsQuery.connectorPollBatchMax("orders-source", ConnectorKind.SOURCE)).thenReturn(null);
        when(kafkaMetricsQuery.connectorRetriesTotal("orders-source")).thenReturn(null);
        when(kafkaMetricsQuery.connectorRecordsPerSec(ConnectorKind.SOURCE, "orders-source", "cdc.team.orders"))
                .thenReturn(null);
        when(kafkaMetricsQuery.connectorRecordsSeries(org.mockito.ArgumentMatchers.eq(ConnectorKind.SOURCE),
                org.mockito.ArgumentMatchers.eq("orders-source"), org.mockito.ArgumentMatchers.eq("cdc.team.orders"),
                anyLong(), anyLong(), anyLong())).thenReturn(Map.of());

        List<ConnectorResponse> connectors = service().listConnectors(wsId, principal, id);

        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).metricsStatus()).isEqualTo("UNAVAILABLE");
        assertThat(connectors.get(0).metricsMessage()).isEqualTo("Prometheus series 없음");
    }

    @Test
    void pauseRejectedWhileCreating() {
        UUID id = UUID.randomUUID();
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(entity(PipelineLifecycle.CREATING)));
        assertThatThrownBy(() -> service().pause(wsId, principal, id))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void deleteRemovesRowAndConnectorResources() {
        UUID id = UUID.randomUUID();
        PipelineEntity p = entity(PipelineLifecycle.ACTIVE);
        p.setSourceConnectorName("src");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));
        stubWorkspace();

        service().delete(wsId, principal, id, false);

        verify(provisioningService).delete(any());
        verify(pipelineRepository).delete(p);
        verify(postgresSlotCleaner).dropSlotIfExists(sourceId, "team-a", p.getId());
    }

    // ---------- helpers ----------

    private void assertValidationFailure(PipelineCreateRequest req) {
        assertThatThrownBy(() -> service().create(wsId, principal, req))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private void stubSource(String readiness) {
        when(datasourceRepository.findByIdAndTenantId(sourceId, wsId))
                .thenReturn(Optional.of(datasource(sourceId, "orders_db", readiness)));
    }

    private void stubSink() {
        when(datasourceRepository.findByIdAndTenantId(sinkId, wsId))
                .thenReturn(Optional.of(datasource(sinkId, "warehouse_db", "OK")));
    }

    private void stubWorkspace() {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(wsId);
        ws.setNamespace("team-a");
        lenient().when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(ws));
    }

    private PipelineEntity withId(org.mockito.invocation.InvocationOnMock inv) {
        PipelineEntity e = inv.getArgument(0);
        if (e.getId() == null) e.setId(UUID.randomUUID());
        return e;
    }

    private static PipelineProvisionResult successResult(PipelineProvisionCommand command, boolean withSink) {
        var connectors = new java.util.ArrayList<PipelineProvisionResult.ConnectorRef>();
        connectors.add(new PipelineProvisionResult.ConnectorRef(
                command.pipelineId() + "-source", ConnectorKind.SOURCE, "mock.Source"));
        if (withSink) {
            connectors.add(new PipelineProvisionResult.ConnectorRef(
                    command.pipelineId() + "-sink", ConnectorKind.SINK, "mock.Sink"));
        }
        return PipelineProvisionResult.success(command.pipelineId(), connectors, "cdc.table.team-a.orders_db");
    }

    private static DatasourceEntity datasource(UUID id, String dbName, String readiness) {
        DatasourceEntity d = new DatasourceEntity();
        d.setId(id);
        d.setDbType(DbType.POSTGRESQL);
        d.setHost("db-host");
        d.setPort(5432);
        d.setDbName(dbName);
        d.setUsername("app");
        d.setSecretRef("secret://" + id);
        d.setCdcReadinessStatus(readiness);
        return d;
    }

    private PipelineEntity entity(PipelineLifecycle status) {
        PipelineEntity p = new PipelineEntity();
        p.setId(UUID.randomUUID());
        p.setTenantId(wsId);
        p.setName("orders");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setSourceDatasourceId(sourceId);
        p.setSchemaName("public");
        p.setTableName("orders");
        p.setStatus(status);
        return p;
    }
}
