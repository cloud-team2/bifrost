package com.bifrost.ops.incident;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.streaming.SsePublisher;
import com.bifrost.ops.event.EventService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private EventService eventService;
    @Mock private SsePublisher ssePublisher;
    @Mock private IncidentAnalysisTrigger incidentAnalysisTrigger;

    private final UUID tenantId = UUID.randomUUID();

    private IncidentService service() {
        return new IncidentService(incidentRepository, eventService, ssePublisher, incidentAnalysisTrigger);
    }

    @Test
    void listUsesStatusFilter() {
        IncidentEntity open = incident(UUID.randomUUID(), tenantId, "OPEN");
        when(incidentRepository.findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, "OPEN"))
                .thenReturn(List.of(open));

        assertThat(service().list(tenantId, "OPEN"))
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.id()).isEqualTo(open.getId());
                    assertThat(response.status()).isEqualTo("OPEN");
                });
        verify(incidentRepository).findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, "OPEN");
    }

    @Test
    void getReturnsIncidentForTenant() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        assertThat(service().get(tenantId, incidentId))
                .satisfies(response -> {
                    assertThat(response.id()).isEqualTo(incidentId);
                    assertThat(response.tenantId()).isEqualTo(tenantId);
                    assertThat(response.status()).isEqualTo("OPEN");
                    assertThat(response.title()).isEqualTo("Orders connector failed");
                    assertThat(response.sourceType()).isEqualTo("CONNECTOR");
                });
    }

    @Test
    void getRejectsMissingIncidentAs404() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(tenantId, incidentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void getRejectsOtherTenantIncidentAs404() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId))
                .thenReturn(Optional.of(incident(incidentId, UUID.randomUUID(), "OPEN")));

        assertThatThrownBy(() -> service().get(tenantId, incidentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void thresholdViolationCreatesIncidentAndLinksEvent() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity incident = inv.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        ArgumentCaptor<IncidentEntity> captor = ArgumentCaptor.forClass(IncidentEntity.class);
        verify(incidentRepository).save(captor.capture());
        IncidentEntity incident = captor.getValue();
        assertThat(incident.getGroupingKey()).isEqualTo(groupingKey);
        assertThat(incident.getSeverity()).isEqualTo("ERROR");
        assertThat(incident.getSourceType()).isEqualTo("PIPELINE");
        assertThat(incident.getSourceId()).isEqualTo(pipelineId);
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.ERROR,
                "PIPELINE_STATUS_CHANGED", "boom", incident.getId(), "PIPELINE");
        verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_opened"), any());
        verify(incidentAnalysisTrigger).startAfterCommit(tenantId, incident.getId(), "Pipeline failed", "boom");
    }

    @Test
    void thresholdViolationLocksGroupBeforeLookup() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity incident = inv.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        InOrder inOrder = inOrder(incidentRepository);
        inOrder.verify(incidentRepository).lockIncidentGroup(tenantId + ":" + groupingKey);
        inOrder.verify(incidentRepository).findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN");
    }

    @Test
    void thresholdViolationPublishesSseAfterCommitWhenTransactionSynchronizationIsActive() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity incident = inv.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                    EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

            verify(ssePublisher, never()).incidentEvent(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_opened"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void thresholdViolationReusesOpenIncidentAndEscalatesWarnSeverity() {
        UUID pipelineId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        incident.setGroupingKey(groupingKey);
        incident.setSeverity("WARN");
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        assertThat(incident.getSeverity()).isEqualTo("ERROR");
        verify(incidentRepository).findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN");
        verify(incidentRepository).save(incident);
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.ERROR,
                "PIPELINE_STATUS_CHANGED", "boom", incidentId, "PIPELINE");
        verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_updated"), any());
        verify(incidentAnalysisTrigger, never()).startAfterCommit(any(), any(), any(), any());
    }

    @Test
    void thresholdViolationReusesOldestOpenIncidentAndClosesDuplicateRows() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        IncidentEntity primary = incident(UUID.randomUUID(), tenantId, "OPEN");
        primary.setGroupingKey(groupingKey);
        IncidentEntity duplicate = incident(UUID.randomUUID(), tenantId, "OPEN");
        duplicate.setGroupingKey(groupingKey);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of(primary, duplicate));
        when(incidentRepository.save(duplicate)).thenReturn(duplicate);

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        assertThat(duplicate.getStatus()).isEqualTo("RESOLVED");
        assertThat(duplicate.getResolvedAt()).isNotNull();
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.ERROR,
                "PIPELINE_STATUS_CHANGED", "boom", primary.getId(), "PIPELINE");
        verify(ssePublisher, times(2)).incidentEvent(eq(tenantId), eq("incident_updated"), any());
    }

    @Test
    void recoveryResolvesOpenIncidentAndReturnsTrue() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        incident.setGroupingKey(groupingKey);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        boolean resolved = service().onRecovery(tenantId, groupingKey,
                "PIPELINE_STATUS_CHANGED", "recovered", pipelineId);

        assertThat(resolved).isTrue();
        assertThat(incident.getStatus()).isEqualTo("RESOLVED");
        assertThat(incident.getResolvedAt()).isNotNull();
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.INFO,
                "PIPELINE_STATUS_CHANGED", "recovered", incidentId, null);
        verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_updated"), any());
    }

    @Test
    void recoveryReturnsFalseWhenNoOpenIncidentExists() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(
                tenantId, groupingKey, "OPEN")).thenReturn(List.of());

        boolean resolved = service().onRecovery(tenantId, groupingKey,
                "PIPELINE_STATUS_CHANGED", "recovered", pipelineId);

        assertThat(resolved).isFalse();
        verify(incidentRepository, never()).save(any());
        verify(eventService, never()).recordWithIncident(any(), any(), any(), any(), any(), any(), any());
    }

    private static IncidentEntity incident(UUID incidentId, UUID tenantId, String status) {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(incidentId);
        incident.setTenantId(tenantId);
        incident.setGroupingKey("connector:orders");
        incident.setSeverity("ERROR");
        incident.setStatus(status);
        incident.setTitle("Orders connector failed");
        incident.setSourceType("CONNECTOR");
        incident.setSourceId(UUID.randomUUID());
        incident.setOpenedAt(Instant.parse("2026-06-09T00:00:00Z"));
        return incident;
    }
}
