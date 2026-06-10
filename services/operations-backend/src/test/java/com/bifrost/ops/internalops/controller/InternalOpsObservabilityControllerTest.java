package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.internalops.dto.AlertListResult;
import com.bifrost.ops.internalops.dto.AlertSummaryResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalOpsObservabilityControllerTest {

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);
    private final InternalOpsObservabilityController controller = new InternalOpsObservabilityController(
            mock(AdminClient.class),
            mock(LokiClient.class),
            mock(JdbcTemplate.class),
            workspaceRepository,
            incidentRepository,
            "http://connect.invalid");

    @Test
    void listAlertsHttpRouteUsesIncidentRowsAndAppliesFiltersAndLimit() throws Exception {
        UUID tenantId = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(tenantId, "proj-001");
        when(workspaceRepository.findById(tenantId)).thenReturn(Optional.of(workspace));
        when(incidentRepository.findByTenantIdAndStatusAndSeverityOrderByOpenedAtDesc(
                eq(tenantId), eq("OPEN"), eq("ERROR"), any(Pageable.class)))
                .thenReturn(List.of(
                        incident("sink failed", "ERROR", "OPEN", "CONNECTOR")));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/alerts", tenantId)
                        .queryParam("status", "open")
                        .queryParam("severity", "error")
                        .queryParam("limit", "1")
                        .header("X-Request-Id", "req-alerts-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.request_id").value("req-alerts-001"))
                .andExpect(jsonPath("$.operation").value("list_alerts"))
                .andExpect(jsonPath("$.result.summary").value("1 alerts matched"))
                .andExpect(jsonPath("$.result.alerts[0].alert_id").isNotEmpty())
                .andExpect(jsonPath("$.result.alerts[0].incident_id").isNotEmpty())
                .andExpect(jsonPath("$.result.alerts[0].summary").value("sink failed"))
                .andExpect(jsonPath("$.result.alerts[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.result.alerts[0].status").value("OPEN"))
                .andExpect(jsonPath("$.result.alerts[0].labels.source_type").value("CONNECTOR"))
                .andExpect(jsonPath("$.result.alerts[0].labels.grouping_key").doesNotExist())
                .andExpect(jsonPath("$.result.alerts[0].labels.source_id").doesNotExist())
                .andExpect(jsonPath("$.result.alerts[0].occurred_at").value("2026-06-09T00:00:00Z"));

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findByTenantIdAndStatusAndSeverityOrderByOpenedAtDesc(
                eq(tenantId), eq("OPEN"), eq("ERROR"), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void listAlertsStillAcceptsNamespaceProjectIdForExistingInternalOpsCallers() {
        UUID tenantId = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(tenantId, "proj-001");
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace));
        when(incidentRepository.findByTenantIdOrderByOpenedAtDesc(eq(tenantId), any(Pageable.class)))
                .thenReturn(List.of(incident("sink failed", "ERROR", "OPEN", "CONNECTOR")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-alerts-002");

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts("proj-001", null, null, null, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().result().alerts()).hasSize(1);
        AlertSummaryResult alert = response.getBody().result().alerts().getFirst();
        assertThat(alert.alertId()).isEqualTo(alert.incidentId());
        assertThat(alert.labels()).containsEntry("source_type", "CONNECTOR");
    }

    @Test
    void listAlertsReturnsFastApiCompatibleErrorEnvelopeForBadLimit() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findById(tenantId)).thenReturn(Optional.of(workspace(tenantId, "proj-001")));

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts(tenantId.toString(), null, null, "0", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().operation()).isEqualTo("list_alerts");
        assertThat(response.getBody().error()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void listAlertsUsesDefaultAndMaxPageLimits() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findById(tenantId)).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(incidentRepository.findByTenantIdOrderByOpenedAtDesc(eq(tenantId), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listAlerts(tenantId.toString(), null, null, null, new MockHttpServletRequest());
        controller.listAlerts(tenantId.toString(), null, null, "999", new MockHttpServletRequest());

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository, times(2)).findByTenantIdOrderByOpenedAtDesc(eq(tenantId), pageCaptor.capture());
        assertThat(pageCaptor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(50, 200);
    }

    @Test
    void listAlertsReturnsFastApiCompatibleErrorEnvelopeForUnknownProject() {
        String projectId = UUID.randomUUID().toString();
        when(workspaceRepository.findById(UUID.fromString(projectId))).thenReturn(Optional.empty());
        when(workspaceRepository.findByNamespace(projectId)).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts(projectId, null, null, null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().operation()).isEqualTo("list_alerts");
        assertThat(response.getBody().error()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void listAlertsReturnsFastApiCompatibleErrorEnvelopeForNonIntegerLimit() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findById(tenantId)).thenReturn(Optional.of(workspace(tenantId, "proj-001")));

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts(tenantId.toString(), null, null, "abc", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void getConnectorTaskTraceEndpointReturnsConnectorTaskTraceOperation() throws Exception {
        // #368 realign: query_traces가 주던 connector task 예외를 별도 도구로 분리.
        // Connect 미연결(http://connect.invalid)이라 traces는 비지만, 엔드포인트·operation은 존재해야 한다.
        mockMvc().perform(get("/internal/ops/projects/{projectId}/connectors/{connectorName}/task-trace",
                        "proj-001", "pipe-conn")
                        .header("X-Request-Id", "req-ctt-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("get_connector_task_trace"))
                .andExpect(jsonPath("$.result.connector").value("pipe-conn"))
                .andExpect(jsonPath("$.result.traces").isArray());
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    private static WorkspaceEntity workspace(UUID id, String namespace) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setName(namespace);
        workspace.setNamespace(namespace);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }

    private static IncidentEntity incident(String title, String severity, String status, String sourceType) {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(UUID.randomUUID());
        incident.setTenantId(UUID.randomUUID());
        incident.setGroupingKey(title + "-key");
        incident.setSeverity(severity);
        incident.setStatus(status);
        incident.setTitle(title);
        incident.setSourceType(sourceType);
        incident.setSourceId(UUID.randomUUID());
        incident.setOpenedAt(Instant.parse("2026-06-09T00:00:00Z"));
        return incident;
    }
}
