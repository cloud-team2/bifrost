package com.bifrost.ops.pipeline.status;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.streaming.SsePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineStatusServiceImplTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private com.bifrost.ops.database.persistence.repository.DatasourceRepository datasourceRepository;
    @Mock private EventService eventService;
    @Mock private IncidentService incidentService;
    @Mock private AuditService auditService;
    @Mock private SsePublisher ssePublisher;
    @Mock private com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository settingsRepository;

    private PipelineStatusServiceImpl service() {
        return new PipelineStatusServiceImpl(pipelineRepository, connectorRepository,
                datasourceRepository, eventService, incidentService, auditService, ssePublisher, settingsRepository);
    }

    // ---------- computeStatus 규칙 ----------

    @Test
    void edaActiveWhenSourceRunning() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.FAN_OUT,
                List.of(connector(ConnectorKind.SOURCE, "RUNNING")))).isEqualTo(PipelineLifecycle.ACTIVE);
    }

    @Test
    void cdcActiveWhenBothRunning() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.DIRECT,
                List.of(connector(ConnectorKind.SOURCE, "RUNNING"), connector(ConnectorKind.SINK, "RUNNING"))))
                .isEqualTo(PipelineLifecycle.ACTIVE);
    }

    @Test
    void cdcCreatingWhenOnlyOneRunning() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.DIRECT,
                List.of(connector(ConnectorKind.SOURCE, "RUNNING"))))
                .isEqualTo(PipelineLifecycle.CREATING);
    }

    @Test
    void failedBeatsEverything() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.DIRECT,
                List.of(connector(ConnectorKind.SOURCE, "RUNNING"), connector(ConnectorKind.SINK, "FAILED"))))
                .isEqualTo(PipelineLifecycle.ERROR);
    }

    @Test
    void partiallyFailedMapsToError() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.FAN_OUT,
                List.of(connector(ConnectorKind.SOURCE, "PARTIALLY_FAILED")))).isEqualTo(PipelineLifecycle.ERROR);
    }

    @Test
    void pausedMapsToPaused() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.FAN_OUT,
                List.of(connector(ConnectorKind.SOURCE, "PAUSED")))).isEqualTo(PipelineLifecycle.PAUSED);
    }

    @Test
    void unassignedStaysCreating() {
        assertThat(PipelineStatusServiceImpl.computeStatus(PipelinePattern.FAN_OUT,
                List.of(connector(ConnectorKind.SOURCE, "UNASSIGNED")))).isEqualTo(PipelineLifecycle.CREATING);
    }

    // ---------- applyConnectorStatus 단일 writer ----------

    @Test
    void applyConnectorStatusTransitionsAndEmits() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setStatus(PipelineLifecycle.CREATING);
        p.setSourceConnectorName(pid + "-source");

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
        verify(pipelineRepository).save(p);
        verify(eventService).record(eq(tenant), eq(pid), eq(EventLevel.INFO), eq("PIPELINE_STATUS_CHANGED"), any());
        verify(auditService).record(eq(tenant), any(), eq("PIPELINE_STATUS_TRANSITION"), eq("PIPELINE"), eq(pid), any());
        verify(ssePublisher).pipelineStatusChanged(tenant, pid, "active");
        verify(ssePublisher).connectorStateChanged(eq(tenant), eq(pid + "-source"), eq("RUNNING"));
    }

    @Test
    void pipelineStatusSsePublishesAfterCommitWhenTransactionSynchronizationIsActive() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setStatus(PipelineLifecycle.CREATING);
        p.setSourceConnectorName(pid + "-source");

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service().applyConnectorStatus(new ConnectorStatusUpdate(
                    pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

            verify(ssePublisher, never()).pipelineStatusChanged(any(), any(), any());
            verify(ssePublisher, never()).connectorStateChanged(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(ssePublisher).pipelineStatusChanged(tenant, pid, "active");
            verify(ssePublisher).connectorStateChanged(tenant, pid + "-source", "RUNNING");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void applyConnectorStatusNoOpWhenStatusUnchanged() {
        UUID pid = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(UUID.randomUUID());
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setStatus(PipelineLifecycle.ACTIVE); // 이미 active

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        when(connectorRepository.findByCrName(any())).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        verify(pipelineRepository, never()).save(any());
        verify(eventService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void errorTransitionOpensIncidentAndLinksStatusEvent() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setStatus(PipelineLifecycle.ACTIVE);
        p.setSourceConnectorName(pid + "-source");

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "FAILED");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        src.setLastError("task failed");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.FAILED, PipelineLifecycle.ERROR, 1, 1, "task failed"));

        verify(incidentService).onThresholdViolation(
                eq(tenant),
                eq(IncidentGroupingKeys.connectorWorker(pid + "-source")),
                eq("CONNECTOR"),
                eq(null),
                eq(EventLevel.ERROR),
                eq("Pipeline 'orders-eda' status ERROR"),
                eq("PIPELINE_STATUS_CHANGED"),
                org.mockito.ArgumentMatchers.contains("task failed"),
                eq(pid));
        verify(eventService, never()).record(eq(tenant), eq(pid), eq(EventLevel.ERROR), eq("PIPELINE_STATUS_CHANGED"), any());
    }

    @Test
    void directSinkFailureGroupsIncidentBySinkConnectorWhenLastErrorIsGeneric() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-sync");
        p.setPattern(PipelinePattern.DIRECT);
        p.setStatus(PipelineLifecycle.ACTIVE);
        p.setSourceConnectorName(pid + "-source");
        p.setSinkConnectorName(pid + "-sink");

        ConnectorEntity source = connector(ConnectorKind.SOURCE, "RUNNING");
        source.setPipelineId(pid);
        source.setCrName(pid + "-source");
        ConnectorEntity sink = connector(ConnectorKind.SINK, "FAILED");
        sink.setPipelineId(pid);
        sink.setCrName(pid + "-sink");
        sink.setLastError("java.sql.BatchUpdateException: duplicate key");

        when(connectorRepository.findByCrName(pid + "-sink")).thenReturn(Optional.of(sink));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(source, sink));

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-sink", ConnectorRuntimeState.FAILED, PipelineLifecycle.ERROR, 1, 1,
                "java.sql.BatchUpdateException: duplicate key"));

        verify(incidentService).onThresholdViolation(
                eq(tenant),
                eq(IncidentGroupingKeys.connectorWorker(pid + "-sink")),
                eq("CONNECTOR"),
                eq(null),
                eq(EventLevel.ERROR),
                eq("Pipeline 'orders-sync' status ERROR"),
                eq("PIPELINE_STATUS_CHANGED"),
                org.mockito.ArgumentMatchers.contains("connector '" + pid + "-sink' FAILED"),
                eq(pid));
    }

    @Test
    void sourceDbErrorGroupsIncidentByDatasource() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setSourceDatasourceId(sourceId);
        p.setStatus(PipelineLifecycle.ACTIVE);

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(sourceId);
        datasource.setName("orders-db");
        datasource.setConnectionStatus("UNREACHABLE");
        datasource.setConnectionError("timeout");

        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));
        when(datasourceRepository.findById(sourceId)).thenReturn(Optional.of(datasource));

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        verify(incidentService).onThresholdViolation(
                eq(tenant),
                eq(IncidentGroupingKeys.datasource(sourceId)),
                eq("DATABASE"),
                eq(sourceId),
                eq(EventLevel.ERROR),
                eq("Pipeline 'orders-eda' status ERROR"),
                eq("PIPELINE_STATUS_CHANGED"),
                org.mockito.ArgumentMatchers.contains("source DB 'orders-db' 연결 불가"),
                eq(pid));
    }

    @Test
    void sameErrorStatusWithChangedCauseResolvesOldGroupingAndOpensNewGrouping() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setSourceDatasourceId(sourceId);
        p.setStatus(PipelineLifecycle.ERROR);
        p.setStatusMessage("source DB 'orders-db' 연결 불가: timeout");
        p.setSourceConnectorName(pid + "-source");

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "FAILED");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        src.setLastError("connector task failed");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));
        when(datasourceRepository.findById(sourceId)).thenReturn(Optional.empty());

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.FAILED, PipelineLifecycle.ERROR, 1, 1, "connector task failed"));

        verify(incidentService).onRecovery(eq(tenant), eq(IncidentGroupingKeys.datasource(sourceId)),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("connector task failed"), eq(pid));
        verify(incidentService).onThresholdViolation(eq(tenant), eq(IncidentGroupingKeys.connectorWorker(pid + "-source")),
                eq("CONNECTOR"), eq(null), eq(EventLevel.ERROR), eq("Pipeline 'orders-eda' status ERROR"),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("connector task failed"), eq(pid));
    }

    @Test
    void consumerLagAboveWarningTransitionsActiveToLagWithoutOpeningIncident() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.DIRECT);
        p.setStatus(PipelineLifecycle.ACTIVE);

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        ConnectorEntity sink = connector(ConnectorKind.SINK, "RUNNING");
        sink.setPipelineId(pid);
        sink.setCrName(pid + "-sink");
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src, sink));

        service().applyConsumerLag(pid, 6_000L);

        verify(eventService).record(eq(tenant), eq(pid), eq(EventLevel.WARN),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("consumer lag 6000"));
        verify(incidentService, never()).onThresholdViolation(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consumerLagRecoveryTransitionsLagToActive() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-sync");
        p.setPattern(PipelinePattern.DIRECT);
        p.setStatus(PipelineLifecycle.LAG);

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        ConnectorEntity sink = connector(ConnectorKind.SINK, "RUNNING");
        sink.setPipelineId(pid);
        sink.setCrName(pid + "-sink");
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src, sink));

        service().applyConsumerLag(pid, 100L);

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
        verify(eventService).record(eq(tenant), eq(pid), eq(EventLevel.INFO),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("LAG → ACTIVE"));
        verify(incidentService, never()).onRecovery(any(), any(), any(), any(), any());
    }

    @Test
    void consumerLagTransitionResolvesPreviousErrorIncidentAndRecordsWarningEvent() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setSourceDatasourceId(sourceId);
        p.setStatus(PipelineLifecycle.ERROR);
        p.setStatusMessage("source DB 'orders-db' 연결 불가: timeout");

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));
        when(datasourceRepository.findById(sourceId)).thenReturn(Optional.empty());

        service().applyConsumerLag(pid, 6_000L);

        verify(incidentService).onRecovery(eq(tenant), eq(IncidentGroupingKeys.datasource(sourceId)),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("ERROR → LAG"), eq(pid));
        verify(eventService).record(eq(tenant), eq(pid), eq(EventLevel.WARN),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("consumer lag 6000"));
    }

    @Test
    void activeTransitionResolvesOpenIncidentInsteadOfWritingPlainEvent() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setStatus(PipelineLifecycle.ERROR);

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));
        when(incidentService.onRecovery(eq(tenant), eq(IncidentGroupingKeys.pipelineAvailability(pid)),
                eq("PIPELINE_STATUS_CHANGED"), any(), eq(pid))).thenReturn(true);

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        verify(incidentService).onRecovery(eq(tenant), eq(IncidentGroupingKeys.pipelineAvailability(pid)),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("ERROR → ACTIVE"), eq(pid));
        verify(eventService, never()).record(eq(tenant), eq(pid), eq(EventLevel.INFO), eq("PIPELINE_STATUS_CHANGED"), any());
    }

    @Test
    void activeTransitionResolvesConnectorIncidentUsingStoredConnectorFailureMessage() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        String sinkName = pid + "-sink";
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-sync");
        p.setPattern(PipelinePattern.DIRECT);
        p.setStatus(PipelineLifecycle.ERROR);
        p.setStatusMessage("connector '" + sinkName + "' FAILED: java.sql.BatchUpdateException");
        p.setSourceConnectorName(pid + "-source");
        p.setSinkConnectorName(sinkName);

        ConnectorEntity source = connector(ConnectorKind.SOURCE, "RUNNING");
        source.setPipelineId(pid);
        source.setCrName(pid + "-source");
        ConnectorEntity sink = connector(ConnectorKind.SINK, "RUNNING");
        sink.setPipelineId(pid);
        sink.setCrName(sinkName);
        when(connectorRepository.findByCrName(sinkName)).thenReturn(Optional.of(sink));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(source, sink));
        when(incidentService.onRecovery(eq(tenant), eq(IncidentGroupingKeys.connectorWorker(sinkName)),
                eq("PIPELINE_STATUS_CHANGED"), any(), eq(pid))).thenReturn(true);

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                sinkName, ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
        verify(incidentService).onRecovery(eq(tenant), eq(IncidentGroupingKeys.connectorWorker(sinkName)),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("ERROR → ACTIVE"), eq(pid));
        verify(eventService, never()).record(eq(tenant), eq(pid), eq(EventLevel.INFO), eq("PIPELINE_STATUS_CHANGED"), any());
    }

    @Test
    void activeTransitionWritesPlainEventWhenNoIncidentWasOpen() {
        UUID pid = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-eda");
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setStatus(PipelineLifecycle.ERROR);

        ConnectorEntity src = connector(ConnectorKind.SOURCE, "RUNNING");
        src.setPipelineId(pid);
        src.setCrName(pid + "-source");
        when(connectorRepository.findByCrName(pid + "-source")).thenReturn(Optional.of(src));
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(List.of(src));

        service().applyConnectorStatus(new ConnectorStatusUpdate(
                pid + "-source", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        verify(incidentService).onRecovery(eq(tenant), eq(IncidentGroupingKeys.pipelineAvailability(pid)),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("ERROR → ACTIVE"), eq(pid));
        verify(eventService).record(eq(tenant), eq(pid), eq(EventLevel.INFO),
                eq("PIPELINE_STATUS_CHANGED"), org.mockito.ArgumentMatchers.contains("ERROR → ACTIVE"));
    }

    private static ConnectorEntity connector(ConnectorKind kind, String state) {
        ConnectorEntity c = new ConnectorEntity();
        c.setKind(kind);
        c.setState(state);
        c.setTasksMax(kind == ConnectorKind.SOURCE ? 1 : 3);
        return c;
    }
}
