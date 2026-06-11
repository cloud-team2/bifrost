package com.bifrost.ops.pipeline.status;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.governance.audit.AuditService;
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
    @Mock private AuditService auditService;
    @Mock private SsePublisher ssePublisher;
    @Mock private com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository settingsRepository;

    private PipelineStatusServiceImpl service() {
        return new PipelineStatusServiceImpl(pipelineRepository, connectorRepository,
                datasourceRepository, eventService, auditService, ssePublisher, settingsRepository);
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
        // 스펙 B.4: Connector Task FAILED → error. lag는 consumer lag로만 산정(#559).
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

    // ---------- consumer lag → status (#559, 스펙 B.1) ----------

    private PipelineEntity cdcPipeline(UUID pid, UUID tenant, PipelineLifecycle status) {
        PipelineEntity p = new PipelineEntity();
        p.setId(pid);
        p.setTenantId(tenant);
        p.setName("orders-sync");
        p.setPattern(PipelinePattern.DIRECT);
        p.setStatus(status);
        return p; // source/sink datasourceId = null → dbUnreachableReason 무시
    }

    private List<ConnectorEntity> twoRunning() {
        return List.of(connector(ConnectorKind.SOURCE, "RUNNING"), connector(ConnectorKind.SINK, "RUNNING"));
    }

    @Test
    void consumerLagAboveWarningTransitionsActiveToLag() {
        UUID pid = UUID.randomUUID(); UUID tenant = UUID.randomUUID();
        PipelineEntity p = cdcPipeline(pid, tenant, PipelineLifecycle.ACTIVE);
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(twoRunning());
        // settingsRepository.findById → 미스텁(empty) → 기본 warning 5,000

        service().applyConsumerLag(pid, 6_000L);

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.LAG);
        verify(ssePublisher).pipelineStatusChanged(tenant, pid, "lag");
        verify(eventService).record(eq(tenant), eq(pid), eq(EventLevel.WARN), eq("PIPELINE_STATUS_CHANGED"), any());
    }

    @Test
    void consumerLagRecoveryTransitionsLagToActive() {
        UUID pid = UUID.randomUUID(); UUID tenant = UUID.randomUUID();
        PipelineEntity p = cdcPipeline(pid, tenant, PipelineLifecycle.LAG);
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(twoRunning());

        service().applyConsumerLag(pid, 100L); // < 5,000

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ACTIVE);
        verify(ssePublisher).pipelineStatusChanged(tenant, pid, "active");
    }

    @Test
    void consumerLagDoesNotOverrideError() {
        UUID pid = UUID.randomUUID(); UUID tenant = UUID.randomUUID();
        PipelineEntity p = cdcPipeline(pid, tenant, PipelineLifecycle.ACTIVE);
        when(pipelineRepository.findById(pid)).thenReturn(Optional.of(p));
        when(connectorRepository.findByPipelineId(pid)).thenReturn(
                List.of(connector(ConnectorKind.SOURCE, "RUNNING"), connector(ConnectorKind.SINK, "FAILED")));

        service().applyConsumerLag(pid, 9_999_999L); // 큰 lag지만 커넥터 FAILED가 우선

        assertThat(p.getStatus()).isEqualTo(PipelineLifecycle.ERROR);
    }

    private static ConnectorEntity connector(ConnectorKind kind, String state) {
        ConnectorEntity c = new ConnectorEntity();
        c.setKind(kind);
        c.setState(state);
        c.setTasksMax(kind == ConnectorKind.SOURCE ? 1 : 3);
        return c;
    }
}
