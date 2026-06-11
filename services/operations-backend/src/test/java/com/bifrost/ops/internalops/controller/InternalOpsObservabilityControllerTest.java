package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.internalops.dto.AlertListResult;
import com.bifrost.ops.internalops.dto.AlertSummaryResult;
import com.bifrost.ops.internalops.dto.ConsumerLagResult;
import com.bifrost.ops.internalops.dto.MetricsResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.monitoring.query.ObservabilityMetricsQuery;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
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
import org.springframework.test.util.ReflectionTestUtils;
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
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final ObservabilityMetricsQuery metricsQuery = mock(ObservabilityMetricsQuery.class);
    private final com.bifrost.ops.monitoring.query.TraceQuery traceQuery =
            mock(com.bifrost.ops.monitoring.query.TraceQuery.class);
    private final InternalOpsObservabilityController controller = new InternalOpsObservabilityController(
            adminClient,
            lokiClient,
            workspaceRepository,
            pipelineRepository,
            connectorRepository,
            incidentRepository,
            eventRepository,
            metricsQuery,
            traceQuery,
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
    void consumerGroupsListsProjectConnectorsAndDoesNotFakeLagWhenKafkaUnavailable() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(pipeline(pipelineId, tenantId, "orders-cdc")));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId))
                .thenReturn(List.of(
                        connector(pipelineId, "orders-source", ConnectorKind.SOURCE),
                        connector(pipelineId, "orders-sink", ConnectorKind.SINK)));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/kafka/consumer-groups", "proj-001")
                        .header("X-Request-Id", "req-cg-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("get_consumer_groups"))
                .andExpect(jsonPath("$.result.consumerGroups[0].group").value("connect-orders-sink"))
                .andExpect(jsonPath("$.result.consumerGroups[0].owner").value("orders-cdc"))
                .andExpect(jsonPath("$.result.consumerGroups[0].state").value("UNKNOWN"))
                .andExpect(jsonPath("$.result.consumerGroups[0].lag").doesNotExist())
                .andExpect(jsonPath("$.result.consumerGroups[0].error").isNotEmpty())
                .andExpect(jsonPath("$.result.consumerGroups[1]").doesNotExist());
    }

    @Test
    void eventIncidentSummaryUsesIncidentAndEventRowsWithWindowAndWarnPlus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IncidentEntity critical = incident("sink failed", "ERROR", "OPEN", "CONNECTOR");
        critical.setTenantId(tenantId);
        critical.setOpenedAt(Instant.now());
        EventEntity warning = event(tenantId, EventLevel.WARN, "CONSUMER_LAG_WARNING", "consumer lag 경고");

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(incidentRepository.findByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
                eq(tenantId), eq("OPEN"), eq(List.of("WARN", "ERROR", "CRITICAL")), any(Instant.class)))
                .thenReturn(List.of(critical));
        when(eventRepository.findByTenantIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(tenantId), eq(List.of(EventLevel.WARN, EventLevel.ERROR)), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(warning));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/events/summary", "proj-001")
                        .queryParam("window", "2h")
                        .queryParam("level", "warn+")
                        .header("X-Request-Id", "req-events-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("analyze_event_log"))
                .andExpect(jsonPath("$.result.window").value("2h"))
                .andExpect(jsonPath("$.result.level").value("warn+"))
                .andExpect(jsonPath("$.result.openIncidents").value(1))
                .andExpect(jsonPath("$.result.criticalIncidents").value(1))
                .andExpect(jsonPath("$.result.critical[0].title").value("sink failed"))
                .andExpect(jsonPath("$.result.warnings[0].type").value("CONSUMER_LAG_WARNING"));
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
    void consumerLagReturnsErrorEnvelopeWhenKafkaLagReadFails() {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector(pipelineId, "orders-sink")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId))
                .thenReturn(Optional.of(pipeline(pipelineId, tenantId, "orders-cdc")));

        ResponseEntity<OpsEnvelope<ConsumerLagResult>> response =
                controller.consumerLag("proj-001", "connect-orders-sink", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().result()).isNull();
        assertThat(response.getBody().error().code()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void queryTracesRejectsConnectorOutsideProjectBeforeConnectRest() {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector(pipelineId, "orders-sink")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<com.bifrost.ops.internalops.dto.TraceSummaryResult>> response =
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
    void searchLogsResolvesProjectByWorkspaceUuid() {
        // #423: 프론트 챗봇은 project_id로 workspace UUID를 보낸다. findById로 해석해 namespace로 스코프해야 한다.
        UUID tenantId = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(tenantId, "proj-001");
        when(workspaceRepository.findById(tenantId)).thenReturn(Optional.of(workspace));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        ResponseEntity<OpsEnvelope<com.bifrost.ops.internalops.dto.LogSearchResult>> response =
                controller.searchLogs(tenantId.toString(), java.util.Map.of("query", "pipeline events", "limit", 10),
                        new MockHttpServletRequest());

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

    @Test
    void queryMetricsReturnsMetricsResultFromQueryService() {
        // #391: workspace 확인 후 ObservabilityMetricsQuery 결과를 OpsEnvelope로 200 반환.
        UUID tenantId = UUID.randomUUID();
        WorkspaceEntity workspace = workspace(tenantId, "proj-001");
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace));
        MetricsResult expected = MetricsResult.of(
                "pipeline_lag_seconds",
                "pipeline_lag_seconds: 1 points, latest=3.000",
                List.of(new MetricsResult.MetricsDataPoint("2026-06-10T00:00:00Z", 3.0)));
        when(metricsQuery.query(eq(workspace), eq("pipeline_lag_seconds"), eq("last_30m"))).thenReturn(expected);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-metrics-001");
        ResponseEntity<OpsEnvelope<MetricsResult>> response =
                controller.queryMetrics("proj-001", "pipeline_lag_seconds", "last_30m", request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().operation()).isEqualTo("query_metrics");
        assertThat(response.getBody().result().metric()).isEqualTo("pipeline_lag_seconds");
        assertThat(response.getBody().result().dataPoints()).hasSize(1);
        assertThat(response.getBody().result().dataPoints().getFirst().value()).isEqualTo(3.0);
    }

    @Test
    void queryMetricsReturnsFastApiCompatibleErrorEnvelopeForUnknownProject() {
        when(workspaceRepository.findByNamespace("missing-project")).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<MetricsResult>> response =
                controller.queryMetrics("missing-project", "pipeline_lag_seconds", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().operation()).isEqualTo("query_metrics");
        assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_FOUND");
        verifyNoInteractions(metricsQuery);
    }

    @Test
    void getConnectorTaskTraceEndpointReturnsConnectorTaskTraceOperation() throws Exception {
        // #368 realign: query_traces가 주던 connector task 예외를 별도 도구로 분리.
        // #379 거버넌스 게이트: task-trace도 프로젝트 소유권 검증을 통과해야 Connect REST에 닿는다.
        // Connect 미연결(http://connect.invalid)이라 traces는 비지만, 엔드포인트·operation은 존재해야 한다.
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("pipe-conn")).thenReturn(Optional.of(connector(pipelineId, "pipe-conn")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId))
                .thenReturn(Optional.of(mock(com.bifrost.ops.pipeline.persistence.entity.PipelineEntity.class)));

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

    private static ConnectorEntity connector(UUID pipelineId, String crName) {
        return connector(pipelineId, crName, ConnectorKind.SINK);
    }

    private static ConnectorEntity connector(UUID pipelineId, String crName, ConnectorKind kind) {
        ConnectorEntity connector = new ConnectorEntity();
        connector.setId(UUID.randomUUID());
        connector.setPipelineId(pipelineId);
        connector.setCrName(crName);
        connector.setKind(kind);
        connector.setConnectorClass("io.confluent.connect.jdbc.JdbcSinkConnector");
        connector.setTasksMax(1);
        return connector;
    }

    private static PipelineEntity pipeline(UUID pipelineId, UUID tenantId, String name) {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setTenantId(tenantId);
        pipeline.setName(name);
        return pipeline;
    }

    private static EventEntity event(UUID tenantId, EventLevel level, String type, String message) {
        EventEntity event = new EventEntity();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setLevel(level);
        event.setType(type);
        event.setMessage(message);
        ReflectionTestUtils.setField(event, "createdAt", Instant.now());
        return event;
    }
}
