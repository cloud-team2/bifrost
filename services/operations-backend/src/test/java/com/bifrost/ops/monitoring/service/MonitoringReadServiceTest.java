package com.bifrost.ops.monitoring.service;

import com.bifrost.ops.database.health.DatabaseHealthProbeJob;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringReadServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private DatasourceRepository datasourceRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private AdminClient adminClient;

    private final UUID tenantId = UUID.randomUUID();

    private MonitoringReadService service() {
        return new MonitoringReadService(pipelineRepository, datasourceRepository, incidentRepository,
                connectorRepository, adminClient);
    }

    @Test
    void overviewCountsOpenIncidentsFromRepository() {
        when(pipelineRepository.countByTenantId(tenantId)).thenReturn(5L);
        when(pipelineRepository.countByTenantIdAndStatus(tenantId, PipelineLifecycle.ACTIVE)).thenReturn(3L);
        when(pipelineRepository.countByTenantIdAndStatus(tenantId, PipelineLifecycle.ERROR)).thenReturn(1L);
        when(datasourceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(datasource(DatabaseHealthProbeJob.HEALTHY),
                        datasource(DatabaseHealthProbeJob.UNREACHABLE)));
        when(incidentRepository.countByTenantIdAndStatus(tenantId, "OPEN")).thenReturn(2L);
        when(connectorRepository.countByTenantId(tenantId)).thenReturn(4L);
        when(connectorRepository.countByTenantIdAndStateIn(
                tenantId,
                ConnectorRuntimeState.FAILED.name(),
                ConnectorRuntimeState.PARTIALLY_FAILED.name())).thenReturn(1L);

        OverviewResponse overview = service().overview(tenantId);

        assertThat(overview.totalPipelines()).isEqualTo(5L);
        assertThat(overview.runningPipelines()).isEqualTo(3L);
        assertThat(overview.errorPipelines()).isEqualTo(1L);
        assertThat(overview.healthyDatabases()).isEqualTo(1L);
        assertThat(overview.unreachableDatabases()).isEqualTo(1L);
        assertThat(overview.openIncidents()).isEqualTo(2L);
        assertThat(overview.totalConnectors()).isEqualTo(4L);
        assertThat(overview.failedConnectors()).isEqualTo(1L);
        verify(incidentRepository).countByTenantIdAndStatus(tenantId, "OPEN");
        verify(connectorRepository).countByTenantId(tenantId);
        verify(connectorRepository).countByTenantIdAndStateIn(
                tenantId,
                ConnectorRuntimeState.FAILED.name(),
                ConnectorRuntimeState.PARTIALLY_FAILED.name());
    }

    private static DatasourceEntity datasource(String connectionStatus) {
        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setConnectionStatus(connectionStatus);
        return datasource;
    }
}
