package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.internalops.dto.AlertListResult;
import com.bifrost.ops.internalops.dto.AlertSummaryResult;
import com.bifrost.ops.internalops.dto.ConsumerLagResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalOpsObservabilityControllerTest {

    private final AdminClient adminClient = mock(AdminClient.class);
    private final LokiClient lokiClient = mock(LokiClient.class);
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final PipelineRepository pipelineRepository = mock(PipelineRepository.class);
    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);
    private final InternalOpsObservabilityController controller = new InternalOpsObservabilityController(
            adminClient,
            lokiClient,
            workspaceRepository,
            pipelineRepository,
            connectorRepository,
            incidentRepository,
            "http://connect.invalid");

    @Test
    void listAlertsHttpRouteUsesIncidentRowsAndAppliesFiltersAndLimit() throws Exception {
        UUID tenantId = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(tenantId, "proj-001");
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace));
        when(incidentRepository.findByTenantIdAndStatusAndSeverityOrderByOpenedAtDesc(
                eq(tenantId), eq("OPEN"), eq("ERROR"), any(Pageable.class)))
                .thenReturn(List.of(
                        incident("sink failed", "ERROR", "OPEN", "CONNECTOR")));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/alerts", "proj-001")
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
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts("proj-001", null, null, "0", new MockHttpServletRequest());

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
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(incidentRepository.findByTenantIdOrderByOpenedAtDesc(eq(tenantId), any(Pageable.class)))
                .thenReturn(List.of());

        controller.listAlerts("proj-001", null, null, null, new MockHttpServletRequest());
        controller.listAlerts("proj-001", null, null, "999", new MockHttpServletRequest());

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository, times(2)).findByTenantIdOrderByOpenedAtDesc(eq(tenantId), pageCaptor.capture());
        assertThat(pageCaptor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(50, 200);
    }

    @Test
    void listAlertsReturnsFastApiCompatibleErrorEnvelopeForUnknownProject() {
        String projectId = "missing-project";
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
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts("proj-001", null, null, "abc", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void consumerLagRejectsGroupWhenProjectOwnershipCannotBeProven() {
        ResponseEntity<OpsEnvelope<ConsumerLagResult>> response =
                controller.consumerLag("proj-001", "orders-consumer", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_FOUND");
        verifyNoInteractions(adminClient);
    }

    @Test
    void consumerLagRejectsConnectManagedGroupOutsideProjectBeforeKafkaAdmin() {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector(pipelineId, "orders-sink")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<ConsumerLagResult>> response =
                controller.consumerLag("proj-001", "connect-orders-sink", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_OWNED_BY_PROJECT");
        verifyNoInteractions(adminClient);
    }

    @Test
    void queryTracesRejectsConnectorOutsideProjectBeforeConnectRest() {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector(pipelineId, "orders-sink")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<java.util.Map<String, Object>>> response =
                controller.queryTraces("proj-001", "orders-sink", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_OWNED_BY_PROJECT");
    }

    @Test
    void incidentSummaryQueriesIncidentWithinProjectTenantOnly() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        IncidentEntity incident = incident("sink failed", "ERROR", "OPEN", "CONNECTOR");
        incident.setId(incidentId);
        incident.setTenantId(tenantId);
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/incidents/{incidentId}/summary",
                        "proj-001", incidentId)
                        .header("X-Request-Id", "req-incident-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("get_incident_summary"))
                .andExpect(jsonPath("$.result.incidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$.result.status").value("OPEN"))
                .andExpect(jsonPath("$.result.note").value("severity=ERROR title=sink failed"));
    }

    @Test
    void legacyIncidentSummaryPathReturnsProjectScopeRequiredError() throws Exception {
        UUID incidentId = UUID.randomUUID();

        mockMvc().perform(get("/internal/ops/incidents/{incidentId}/summary", incidentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.operation").value("get_incident_summary"))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.required_action").value("use_project_scoped_path"));

        verifyNoInteractions(incidentRepository);
    }

    @Test
    void searchLogsScopesPlainTextQueryToProjectNamespace() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-logs-001");
        ResponseEntity<OpsEnvelope<com.bifrost.ops.internalops.dto.LogSearchResult>> response =
                controller.searchLogs("proj-001", java.util.Map.of("query", "pipeline events", "limit", 10), request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        assertThat(queryCaptor.getValue()).isEqualTo("{namespace=\"proj-001\"} |= \"pipeline events\"");
    }

    @Test
    void searchLogsDoesNotTrustCallerProvidedNamespaceSelector() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        controller.searchLogs(
                "proj-001",
                java.util.Map.of("query", "{namespace=~\"proj-001|other-project\",app=\"worker\"} |= \"error\"", "limit", 10),
                new MockHttpServletRequest());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        assertThat(queryCaptor.getValue()).isEqualTo("{namespace=\"proj-001\",app=\"worker\"} |= \"error\"");
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

    private static ConnectorEntity connector(UUID pipelineId, String crName) {
        ConnectorEntity connector = new ConnectorEntity();
        connector.setId(UUID.randomUUID());
        connector.setPipelineId(pipelineId);
        connector.setCrName(crName);
        connector.setKind(ConnectorKind.SINK);
        connector.setConnectorClass("io.confluent.connect.jdbc.JdbcSinkConnector");
        connector.setTasksMax(1);
        return connector;
    }
}
