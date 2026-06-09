package com.bifrost.ops.incident;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.streaming.SsePublisher;
import com.bifrost.ops.event.EventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private EventService eventService;
    @Mock private SsePublisher ssePublisher;

    private final UUID tenantId = UUID.randomUUID();

    private IncidentService service() {
        return new IncidentService(incidentRepository, eventService, ssePublisher);
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
