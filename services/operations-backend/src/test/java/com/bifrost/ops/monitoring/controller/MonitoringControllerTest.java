package com.bifrost.ops.monitoring.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.event.dto.EventResponse;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.IncidentReportService;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.monitoring.dto.ResourceEventResponse;
import com.bifrost.ops.monitoring.service.MonitoringReadService;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitoringControllerTest {

    private final MonitoringReadService monitoringReadService = mock(MonitoringReadService.class);
    private final IncidentService incidentService = mock(IncidentService.class);
    private final EventService eventService = mock(EventService.class);
    private final IncidentReportService incidentReportService = mock(IncidentReportService.class);
    private final WorkspaceAccessGuard accessGuard = mock(WorkspaceAccessGuard.class);
    private final MonitoringController controller =
            new MonitoringController(monitoringReadService, incidentService, eventService, incidentReportService, accessGuard);

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
    void overviewGetEndpointReturnsJsonAndChecksAccess() throws Exception {
        OverviewResponse overview = new OverviewResponse(3, 2, 1, 4, 0, 1, 5, 1);
        when(monitoringReadService.overview(wsId)).thenReturn(overview);

        mockMvc().perform(get("/api/v1/workspaces/{wsId}/monitoring/overview", wsId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPipelines").value(3))
                .andExpect(jsonPath("$.runningPipelines").value(2))
                .andExpect(jsonPath("$.errorPipelines").value(1))
                .andExpect(jsonPath("$.healthyDatabases").value(4))
                .andExpect(jsonPath("$.unreachableDatabases").value(0))
                .andExpect(jsonPath("$.openIncidents").value(1))
                .andExpect(jsonPath("$.totalConnectors").value(5))
                .andExpect(jsonPath("$.failedConnectors").value(1));

        verify(accessGuard).requireAccess(wsId, principal);
        verify(monitoringReadService).overview(wsId);
    }

    @Test
    void resourceEventsGetEndpointReturnsJsonAndChecksAccess() throws Exception {
        ResourceEventResponse event = new ResourceEventResponse(
                "PARTITION_REASSIGNMENT",
                "orders-2",
                "replicas=[1, 2, 3] addingReplicas=[3]",
                Instant.parse("2026-06-09T00:00:00Z"));
        when(monitoringReadService.resourceEvents(wsId)).thenReturn(List.of(event));

        mockMvc().perform(get("/api/v1/workspaces/{wsId}/monitoring/resource-events", wsId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("PARTITION_REASSIGNMENT"))
                .andExpect(jsonPath("$[0].resource").value("orders-2"))
                .andExpect(jsonPath("$[0].detail").value("replicas=[1, 2, 3] addingReplicas=[3]"))
                .andExpect(jsonPath("$[0].occurredAt").value("2026-06-09T00:00:00Z"));

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
    void incidentDetailReturnsIncidentEventsImpactPipelinesAndReports() {
        UUID incidentId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        IncidentResponse incident = new IncidentResponse(
                incidentId,
                wsId,
                "pipeline:" + pipelineId,
                "ERROR",
                "OPEN",
                "Pipeline lag",
                "lag exceeded threshold",
                "PIPELINE",
                pipelineId,
                Instant.parse("2026-06-09T00:00:00Z"),
                null);
        EventResponse event = new EventResponse(
                UUID.randomUUID(),
                pipelineId,
                incidentId,
                EventLevel.ERROR,
                "LAG_THRESHOLD",
                "lag too high",
                Instant.parse("2026-06-09T00:01:00Z"));
        IncidentReportResponse report = sampleReport(incidentId.toString());
        when(incidentService.get(wsId, incidentId)).thenReturn(incident);
        when(eventService.list(wsId, null, null, incidentId)).thenReturn(List.of(event));
        when(incidentReportService.list(incidentId)).thenReturn(List.of(report));

        var body = controller.incidentDetail(wsId, incidentId, principal).getBody();

        assertThat(body.incident()).isEqualTo(incident);
        assertThat(body.events()).containsExactly(event);
        assertThat(body.impactPipelineIds()).containsExactly(pipelineId);
        assertThat(body.reports()).containsExactly(report);
        verify(accessGuard).requireAccess(wsId, principal);
    }

    @Test
    void incidentReportsValidateWorkspaceIncidentBeforeDelegating() {
        UUID incidentId = UUID.randomUUID();
        IncidentResponse incident = sample(incidentId, wsId, "OPEN");
        IncidentReportResponse report = sampleReport(incidentId.toString());
        when(incidentService.get(wsId, incidentId)).thenReturn(incident);
        when(incidentReportService.list(incidentId)).thenReturn(List.of(report));

        List<IncidentReportResponse> body = controller.incidentReports(wsId, incidentId, principal).getBody();

        assertThat(body).containsExactly(report);
        verify(accessGuard).requireAccess(wsId, principal);
        verify(incidentService).get(wsId, incidentId);
        verify(incidentReportService).list(incidentId);
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

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver(principal))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        JsonMapper.builder()
                                .findAndAddModules()
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    private record AuthenticatedUserArgumentResolver(AuthenticatedUser principal)
            implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                    && AuthenticatedUser.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            return principal;
        }
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

    private static IncidentReportResponse sampleReport(String incidentId) {
        return new IncidentReportResponse(
                UUID.randomUUID().toString(),
                "run_001",
                incidentId,
                "CONNECTOR_TASK_FAILED",
                0.82,
                true,
                JsonMapper.builder().build().createObjectNode().put("answer", "restart connector"),
                Instant.parse("2026-06-09T00:02:00Z"));
    }
}
