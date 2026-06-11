package com.bifrost.ops.pipeline;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.kafka.KafkaResourceCleaner;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.pipeline.service.PipelineService;
import com.bifrost.ops.pipeline.status.PipelineStatusServiceImpl;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.streaming.SsePublisher;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EDA(fan-out) 파이프라인 풀 플로우 컴포넌트 통합테스트 (#359).
 *
 * <p>PipelineService + PipelineStatusServiceImpl + KafkaResourceCleaner를 실제로 엮어
 * 생성→creating→active 전이, error 전이, 삭제 시 EDA 전용 정리를 한 흐름에서 검증한다.
 * 인프라(Kafka, DB)는 mock으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineEdaIntegrationTest {

    // ---- infra mocks ----
    @Mock PipelineRepository pipelineRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock WorkspaceRepository workspaceRepository;
    @Mock ConnectorRepository connectorRepository;
    @Mock PipelineProvisioningService provisioningService;
    @Mock WorkspaceAccessGuard accessGuard;
    @Mock EventService eventService;
    @Mock AuditService auditService;
    @Mock KafkaResourceCleaner kafkaResourceCleaner;
    @Mock SsePublisher ssePublisher;

    // ---- subjects ----
    PipelineService pipelineService;
    PipelineStatusServiceImpl statusService;

    final UUID wsId = UUID.randomUUID();
    final UUID sourceId = UUID.randomUUID();
    final AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

    @BeforeEach
    void setUp() {
        pipelineService = new PipelineService(pipelineRepository, datasourceRepository,
                workspaceRepository, connectorRepository, provisioningService,
                accessGuard, eventService, auditService, kafkaResourceCleaner,
                org.mockito.Mockito.mock(com.bifrost.ops.database.service.CdcReadinessService.class));
        statusService = new PipelineStatusServiceImpl(pipelineRepository, connectorRepository,
                datasourceRepository, eventService, auditService, ssePublisher);
    }

    // ---- 생성 → creating -----------------------------------------------

    @Test
    void edaCreateReturnsCreatingAndProvisionsSourceOnly() {
        stubSource();
        stubWorkspace();
        stubNoDuplicates();
        UUID pipelineId = UUID.randomUUID();
        when(pipelineRepository.saveAndFlush(any())).thenAnswer(inv -> {
            PipelineEntity e = inv.getArgument(0);
            e.setId(pipelineId);
            return e;
        });
        when(provisioningService.provision(any()))
                .thenAnswer(inv -> edaResult(inv.getArgument(0)));

        PipelineResponse resp = pipelineService.create(wsId, principal,
                new PipelineCreateRequest("orders-eda", "fan-out", sourceId, null, "public", "orders"));

        assertThat(resp.status()).isEqualTo("creating");
        assertThat(resp.pattern()).isEqualTo("fan-out");
        assertThat(resp.sinkDbId()).isNull();

        ArgumentCaptor<PipelineProvisionCommand> cmd = ArgumentCaptor.forClass(PipelineProvisionCommand.class);
        verify(provisioningService).provision(cmd.capture());
        assertThat(cmd.getValue().pattern()).isEqualTo(PipelinePattern.FAN_OUT);
        assertThat(cmd.getValue().sink()).isNull();
    }

    // ---- watcher RUNNING → active -------------------------------------

    @Test
    void watcherSourceRunningTransitionsEdaToActive() {
        UUID pid = UUID.randomUUID();
        PipelineEntity p = edaPipeline(pid, PipelineLifecycle.CREATING);

        ConnectorEntity src = sourceConnector(pid, "RUNNING");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        // watcher가 호출하는 경로
        statusService.applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
        verify(pipelineRepository).save(p);
    }

    // ---- watcher FAILED → error ----------------------------------------

    @Test
    void watcherSourceFailedTransitionsEdaToError() {
        UUID pid = UUID.randomUUID();
        PipelineEntity p = edaPipeline(pid, PipelineLifecycle.CREATING);

        ConnectorEntity src = sourceConnector(pid, "FAILED");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        statusService.applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.FAILED, PipelineLifecycle.ERROR, 0, 0, null));

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ERROR);
    }

    // ---- 삭제 → EDA 전용 정리 -----------------------------------------

    @Test
    void edaDeleteCallsKafkaCleanerWithFanOutPattern() {
        UUID pid = UUID.randomUUID();
        PipelineEntity p = edaPipeline(pid, PipelineLifecycle.ACTIVE);
        p.setTopicName("public.orders");
        p.setSourceConnectorName(pid + "-source");
        when(pipelineRepository.findByIdAndTenantId(pid, wsId)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of());

        pipelineService.delete(wsId, principal, pid, false);

        // EDA: FAN_OUT 패턴으로 deleteResources 호출 → sink group 삭제 없음
        ArgumentCaptor<PipelinePattern> patternCaptor = ArgumentCaptor.forClass(PipelinePattern.class);
        verify(kafkaResourceCleaner).deleteResources(eq("public.orders"), eq(pid), patternCaptor.capture());
        assertThat(patternCaptor.getValue()).isEqualTo(PipelinePattern.FAN_OUT);
    }

    // ---- helpers -------------------------------------------------------

    private void stubSource() {
        DatasourceEntity d = new DatasourceEntity();
        d.setId(sourceId);
        d.setDbType(DbType.POSTGRESQL);
        d.setHost("db");
        d.setPort(5432);
        d.setDbName("app_db");
        d.setUsername("app");
        d.setSecretRef("secret://" + sourceId);
        d.setCdcReadinessStatus("OK");
        when(datasourceRepository.findByIdAndTenantId(sourceId, wsId)).thenReturn(Optional.of(d));
    }

    private void stubWorkspace() {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(wsId);
        ws.setNamespace("team-a");
        lenient().when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(ws));
    }

    private void stubNoDuplicates() {
        when(pipelineRepository.existsByTenantIdAndName(wsId, "orders-eda")).thenReturn(false);
        when(pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                any(), any(), any(), any(), any())).thenReturn(false);
    }

    private static PipelineProvisionResult edaResult(PipelineProvisionCommand cmd) {
        return PipelineProvisionResult.success(cmd.pipelineId(),
                List.of(new PipelineProvisionResult.ConnectorRef(
                        cmd.pipelineId() + "-source", ConnectorKind.SOURCE, "mock.Debezium")),
                cmd.pipelineId() + ".public.orders");
    }

    private PipelineEntity edaPipeline(UUID pid, PipelineLifecycle status) {
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(wsId);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setSourceDatasourceId(sourceId);
        p.setSchemaName("public");
        p.setTableName("orders");
        p.setStatus(status);
        return p;
    }

    private static ConnectorEntity sourceConnector(UUID pipelineId, String state) {
        ConnectorEntity c = new ConnectorEntity();
        c.setKind(ConnectorKind.SOURCE);
        c.setState(state);
        c.setTasksMax(1);
        c.setPipelineId(pipelineId);
        c.setCrName(pipelineId + "-source");
        return c;
    }
}
