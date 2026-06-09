package com.bifrost.ops.monitoring.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.monitoring.dto.ResourceEventResponse;
import com.bifrost.ops.monitoring.service.MonitoringReadService;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitoringControllerTest {

    private final MonitoringReadService monitoringReadService = mock(MonitoringReadService.class);
    private final IncidentService incidentService = mock(IncidentService.class);
    private final WorkspaceAccessGuard accessGuard = mock(WorkspaceAccessGuard.class);
    private final MonitoringController controller =
            new MonitoringController(monitoringReadService, incidentService, accessGuard);

    private final UUID wsId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

    @Test
    void overviewDelegatesToMonitoringService() {
        OverviewResponse overview = new OverviewResponse(3, 2, 1, 4, 0, 1, 5, 1);
        when(monitoringReadService.overview(wsId)).thenReturn(overview);

        OverviewResponse body = controller.overview(wsId, principal).getBody();

        assertThat(body).isEqualTo(overview);
        verify(accessGuard).requireAccess(wsId, principal);
        verify(monitoringReadService).overview(wsId);
    }

    @Test
    void resourceEventsDelegatesToMonitoringService() {
        ResourceEventResponse event = new ResourceEventResponse(
                "LEADER_ELECTION",
                "topic:orders",
                "leader changed",
                Instant.parse("2026-06-09T00:00:00Z"));
        when(monitoringReadService.resourceEvents(wsId)).thenReturn(List.of(event));

        List<ResourceEventResponse> body = controller.resourceEvents(wsId, principal).getBody();

        assertThat(body).containsExactly(event);
        verify(accessGuard).requireAccess(wsId, principal);
        verify(monitoringReadService).resourceEvents(wsId);
    }

    @Test
    void incidentsDelegatesToIncidentServiceWithStatusFilter() {
        IncidentResponse incident = sample(UUID.randomUUID(), wsId, "OPEN");
        when(incidentService.list(wsId, "OPEN")).thenReturn(List.of(incident));

        List<IncidentResponse> body = controller.incidents(wsId, "OPEN", principal).getBody();

        assertThat(body).containsExactly(incident);
        verify(accessGuard).requireAccess(wsId, principal);
        verify(incidentService).list(wsId, "OPEN");
    }

    @Test
    void incidentDelegatesToIncidentService() {
        UUID incidentId = UUID.randomUUID();
        IncidentResponse incident = sample(incidentId, wsId, "OPEN");
        when(incidentService.get(wsId, incidentId)).thenReturn(incident);

        IncidentResponse body = controller.incident(wsId, incidentId, principal).getBody();

        assertThat(body).isEqualTo(incident);
        verify(accessGuard).requireAccess(wsId, principal);
        verify(incidentService).get(wsId, incidentId);
    }

    @Test
    void overviewPropagatesForbiddenBeforeServiceCall() {
        doThrow(new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, "워크스페이스 접근 권한이 없습니다"))
                .when(accessGuard).requireAccess(wsId, principal);

        assertThatThrownBy(() -> controller.overview(wsId, principal))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.WORKSPACE_FORBIDDEN));
        verify(monitoringReadService, never()).overview(wsId);
    }

    @Test
    void incidentPropagatesNotFoundAs404() {
        UUID incidentId = UUID.randomUUID();
        doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "incident not found: " + incidentId))
                .when(incidentService).get(wsId, incidentId);

        assertThatThrownBy(() -> controller.incident(wsId, incidentId, principal))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static IncidentResponse sample(UUID incidentId, UUID tenantId, String status) {
        return new IncidentResponse(
                incidentId,
                tenantId,
                "connector:orders",
                "ERROR",
                status,
                "Orders connector failed",
                null,
                "CONNECTOR",
                UUID.randomUUID(),
                Instant.parse("2026-06-09T00:00:00Z"),
                null);
    }
}
