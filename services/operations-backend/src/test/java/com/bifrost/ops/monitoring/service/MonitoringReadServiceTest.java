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
import org.apache.kafka.clients.admin.ListPartitionReassignmentsResult;
import org.apache.kafka.clients.admin.PartitionReassignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @Test
    void resourceEventsMapsPartitionReassignmentWithFiveSecondTimeout() throws Exception {
        TopicPartition topicPartition = new TopicPartition("orders", 2);
        PartitionReassignment reassignment = new PartitionReassignment(
                List.of(1, 2, 3),
                List.of(3),
                List.of(1));
        ListPartitionReassignmentsResult result = mock(ListPartitionReassignmentsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<TopicPartition, PartitionReassignment>> future = mock(KafkaFuture.class);
        when(adminClient.listPartitionReassignments()).thenReturn(result);
        when(result.reassignments()).thenReturn(future);
        when(future.get(5L, TimeUnit.SECONDS)).thenReturn(Map.of(topicPartition, reassignment));

        Instant before = Instant.now();
        var events = service().resourceEvents(tenantId);
        Instant after = Instant.now();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("PARTITION_REASSIGNMENT");
        assertThat(events.get(0).resource()).isEqualTo("orders-2");
        assertThat(events.get(0).detail())
                .contains("replicas=[1, 2, 3]")
                .contains("addingReplicas=[3]");
        assertThat(events.get(0).occurredAt()).isBetween(before, after);
        verify(adminClient).listPartitionReassignments();
        verify(result).reassignments();
        verify(future).get(5L, TimeUnit.SECONDS);
    }

    @Test
    void resourceEventsSwallowsTimeoutAndReturnsEmptyList() throws Exception {
        ListPartitionReassignmentsResult result = mock(ListPartitionReassignmentsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<TopicPartition, PartitionReassignment>> future = mock(KafkaFuture.class);
        when(adminClient.listPartitionReassignments()).thenReturn(result);
        when(result.reassignments()).thenReturn(future);
        when(future.get(5L, TimeUnit.SECONDS)).thenThrow(new TimeoutException("kafka admin timeout"));

        var events = service().resourceEvents(tenantId);

        assertThat(events).isEmpty();
        verify(future).get(5L, TimeUnit.SECONDS);
    }

    private static DatasourceEntity datasource(String connectionStatus) {
        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setConnectionStatus(connectionStatus);
        return datasource;
    }
}
