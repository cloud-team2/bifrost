package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaAdminPollerTest {

    @Mock private AdminClient adminClient;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private WorkspaceSettingsRepository settingsRepository;
    @Mock private EventService eventService;
    @Mock private IncidentService incidentService;
    @Mock private PipelineStatusService pipelineStatusService;

    @Test
    void criticalLagOpensIncidentWithConsumerGroupGrouping() {
        KafkaAdminPoller poller = poller();
        PipelineEntity pipeline = pipeline();
        WorkspaceSettingsEntity settings = settings(pipeline.getTenantId());
        when(settingsRepository.findById(pipeline.getTenantId())).thenReturn(Optional.of(settings));

        ReflectionTestUtils.invokeMethod(poller, "evaluateLag", "connect-orders-sink", 150L, pipeline);

        verify(incidentService).onThresholdViolation(
                eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.consumerLag("connect-orders-sink")),
                eq("CONSUMER_GROUP"),
                eq(null),
                eq(EventLevel.ERROR),
                eq("Consumer lag critical: connect-orders-sink"),
                eq("CONSUMER_LAG_CRITICAL"),
                org.mockito.ArgumentMatchers.contains("lag=150"),
                eq(pipeline.getId()));
        verify(eventService, never()).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.ERROR), eq("CONSUMER_LAG_CRITICAL"), any());
    }

    @Test
    void warningLagRecordsPlainWarnEventWithoutIncident() {
        KafkaAdminPoller poller = poller();
        PipelineEntity pipeline = pipeline();
        WorkspaceSettingsEntity settings = settings(pipeline.getTenantId());
        when(settingsRepository.findById(pipeline.getTenantId())).thenReturn(Optional.of(settings));

        ReflectionTestUtils.invokeMethod(poller, "evaluateLag", "connect-orders-sink", 50L, pipeline);

        verify(eventService).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.WARN), eq("CONSUMER_LAG_WARNING"),
                org.mockito.ArgumentMatchers.contains("lag=50"));
        verify(incidentService, never()).onThresholdViolation(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recoveredLagResolvesOpenIncident() {
        KafkaAdminPoller poller = poller();
        PipelineEntity pipeline = pipeline();
        WorkspaceSettingsEntity settings = settings(pipeline.getTenantId());
        when(settingsRepository.findById(pipeline.getTenantId())).thenReturn(Optional.of(settings));
        when(incidentService.onRecovery(eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.consumerLag("connect-orders-sink")),
                eq("CONSUMER_LAG_RECOVERED"), any(), eq(pipeline.getId()))).thenReturn(true);
        ReflectionTestUtils.invokeMethod(poller, "evaluateLag", "connect-orders-sink", 150L, pipeline);
        clearInvocations(incidentService, eventService);

        ReflectionTestUtils.invokeMethod(poller, "evaluateLag", "connect-orders-sink", 0L, pipeline);

        verify(incidentService).onRecovery(
                eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.consumerLag("connect-orders-sink")),
                eq("CONSUMER_LAG_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("lag=0"),
                eq(pipeline.getId()));
        verify(eventService, never()).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.INFO), eq("CONSUMER_LAG_RECOVERED"), any());
    }

    @Test
    void recoveredLagFallsBackToPlainEventWhenNoIncidentWasOpen() {
        KafkaAdminPoller poller = poller();
        PipelineEntity pipeline = pipeline();
        WorkspaceSettingsEntity settings = settings(pipeline.getTenantId());
        when(settingsRepository.findById(pipeline.getTenantId())).thenReturn(Optional.of(settings));
        ReflectionTestUtils.invokeMethod(poller, "evaluateLag", "connect-orders-sink", 150L, pipeline);
        clearInvocations(incidentService, eventService);

        ReflectionTestUtils.invokeMethod(poller, "evaluateLag", "connect-orders-sink", 0L, pipeline);

        verify(incidentService).onRecovery(
                eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.consumerLag("connect-orders-sink")),
                eq("CONSUMER_LAG_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("lag=0"),
                eq(pipeline.getId()));
        verify(eventService).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.INFO), eq("CONSUMER_LAG_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("lag=0"));
    }

    private KafkaAdminPoller poller() {
        return new KafkaAdminPoller(adminClient, pipelineRepository, settingsRepository,
                eventService, incidentService, pipelineStatusService);
    }

    private static WorkspaceSettingsEntity settings(UUID tenantId) {
        WorkspaceSettingsEntity settings = WorkspaceSettingsEntity.defaults(tenantId);
        settings.setLagWarningThreshold(10L);
        settings.setLagCriticalThreshold(100L);
        return settings;
    }

    private static PipelineEntity pipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setTenantId(UUID.randomUUID());
        pipeline.setName("orders-eda");
        pipeline.setSinkConnectorName("orders-sink");
        return pipeline;
    }
}
