package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import com.bifrost.ops.incident.IncidentGroupingKeys;
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
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
            "platform-kafka",
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
                controller.listAlerts("proj-001", null, null, null, null, null, request);

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
                controller.listAlerts("proj-001", null, null, "0", null, null, new MockHttpServletRequest());

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

        controller.listAlerts("proj-001", null, null, null, null, null, new MockHttpServletRequest());
        controller.listAlerts("proj-001", null, null, "999", null, null, new MockHttpServletRequest());

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
    void listAlertsScopedByPipelineExcludesOtherPipelineIncidents() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sharedDatasourceId = UUID.randomUUID();
        PipelineEntity pipeline = pipeline(pipelineId, tenantId, "orders-cdc");
        pipeline.setSourceDatasourceId(sharedDatasourceId);
        pipeline.setSinkConnectorName("orders-sink");
        IncidentEntity target = incident("orders sink failed", "ERROR", "OPEN", "CONNECTOR");
        target.setTenantId(tenantId);
        target.setGroupingKey(IncidentGroupingKeys.connectorWorker("orders-sink"));

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline));
        when(connectorRepository.findByPipelineId(pipelineId))
                .thenReturn(List.of(connector(pipelineId, "orders-sink", ConnectorKind.SINK)));
        when(incidentRepository.findScopedAlertsByTenantIdOrderByOpenedAtDesc(
                eq(tenantId),
                isNull(),
                isNull(),
                argThat(keys -> keys.contains(IncidentGroupingKeys.datasource(sharedDatasourceId))
                        && keys.contains(IncidentGroupingKeys.connectorWorker("orders-sink"))),
                argThat(ids -> ids.contains(pipelineId) && ids.contains(sharedDatasourceId)),
                eq(pipelineId),
                any(Pageable.class)))
                .thenReturn(List.of(target));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/alerts", "proj-001")
                        .queryParam("pipeline_id", pipelineId.toString())
                        .header("X-Request-Id", "req-alerts-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("list_alerts"))
                .andExpect(jsonPath("$.result.summary").value("1 alerts matched"))
                .andExpect(jsonPath("$.result.alerts[0].summary").value("orders sink failed"))
                .andExpect(jsonPath("$.result.alerts[1]").doesNotExist());
    }

    @Test
    void listAlertsScopedByConnectorExcludesSiblingConnectorIncidents() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sinkDatasourceId = UUID.randomUUID();
        PipelineEntity pipeline = pipeline(pipelineId, tenantId, "orders-cdc");
        pipeline.setSinkDatasourceId(sinkDatasourceId);
        ConnectorEntity connector = connector(pipelineId, "orders-sink", ConnectorKind.SINK);
        IncidentEntity target = incident("orders sink lag", "WARN", "OPEN", "CONSUMER_GROUP");
        target.setTenantId(tenantId);
        target.setGroupingKey(IncidentGroupingKeys.consumerLag("connect-orders-sink"));

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline));
        when(incidentRepository.findScopedAlertsByTenantIdOrderByOpenedAtDesc(
                eq(tenantId),
                isNull(),
                isNull(),
                argThat(keys -> keys.contains(IncidentGroupingKeys.datasource(sinkDatasourceId))
                        && keys.contains(IncidentGroupingKeys.connectorWorker("orders-sink"))
                        && keys.contains(IncidentGroupingKeys.consumerLag("connect-orders-sink"))),
                argThat(ids -> ids.contains(connector.getId()) && ids.contains(sinkDatasourceId)),
                eq(pipelineId),
                any(Pageable.class)))
                .thenReturn(List.of(target));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/alerts", "proj-001")
                        .queryParam("connector_name", "orders-sink")
                        .header("X-Request-Id", "req-alerts-connector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("list_alerts"))
                .andExpect(jsonPath("$.result.summary").value("1 alerts matched"))
                .andExpect(jsonPath("$.result.alerts[0].summary").value("orders sink lag"))
                .andExpect(jsonPath("$.result.alerts[1]").doesNotExist());
    }

    @Test
    void listAlertsScopedByPipelineKeepsDatabaseIncidentWithPipelineEventOwnership() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sourceDatasourceId = UUID.randomUUID();
        PipelineEntity pipeline = pipeline(pipelineId, tenantId, "orders-cdc");
        pipeline.setSourceDatasourceId(sourceDatasourceId);
        IncidentEntity ownedDatabase = incident("orders source DB failed", "CRITICAL", "OPEN", "DATABASE");
        ownedDatabase.setTenantId(tenantId);
        ownedDatabase.setGroupingKey(IncidentGroupingKeys.datasource(sourceDatasourceId));
        ownedDatabase.setSourceId(sourceDatasourceId);

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline));
        when(connectorRepository.findByPipelineId(pipelineId)).thenReturn(List.of());
        when(incidentRepository.findScopedAlertsByTenantIdOrderByOpenedAtDesc(
                eq(tenantId),
                isNull(),
                isNull(),
                argThat(keys -> keys.contains(IncidentGroupingKeys.datasource(sourceDatasourceId))),
                argThat(ids -> ids.contains(pipelineId) && ids.contains(sourceDatasourceId)),
                eq(pipelineId),
                any(Pageable.class)))
                .thenReturn(List.of(ownedDatabase));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/alerts", "proj-001")
                        .queryParam("pipeline_id", pipelineId.toString())
                        .header("X-Request-Id", "req-alerts-pipeline-db"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("list_alerts"))
                .andExpect(jsonPath("$.result.summary").value("1 alerts matched"))
                .andExpect(jsonPath("$.result.alerts[0].summary").value("orders source DB failed"))
                .andExpect(jsonPath("$.result.alerts[1]").doesNotExist());
    }

    @Test
    void listAlertsScopedByConnectorKeepsDatabaseIncidentWithPipelineEventOwnership() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sinkDatasourceId = UUID.randomUUID();
        PipelineEntity pipeline = pipeline(pipelineId, tenantId, "orders-cdc");
        pipeline.setSinkDatasourceId(sinkDatasourceId);
        ConnectorEntity connector = connector(pipelineId, "orders-sink", ConnectorKind.SINK);
        IncidentEntity ownedDatabase = incident("orders sink DB failed", "CRITICAL", "OPEN", "DATABASE");
        ownedDatabase.setTenantId(tenantId);
        ownedDatabase.setGroupingKey(IncidentGroupingKeys.datasource(sinkDatasourceId));
        ownedDatabase.setSourceId(sinkDatasourceId);

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline));
        when(incidentRepository.findScopedAlertsByTenantIdOrderByOpenedAtDesc(
                eq(tenantId),
                isNull(),
                isNull(),
                argThat(keys -> keys.contains(IncidentGroupingKeys.datasource(sinkDatasourceId))
                        && keys.contains(IncidentGroupingKeys.connectorWorker("orders-sink"))),
                argThat(ids -> ids.contains(connector.getId()) && ids.contains(sinkDatasourceId)),
                eq(pipelineId),
                any(Pageable.class)))
                .thenReturn(List.of(ownedDatabase));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/alerts", "proj-001")
                        .queryParam("connector_name", "orders-sink")
                        .header("X-Request-Id", "req-alerts-connector-db"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("list_alerts"))
                .andExpect(jsonPath("$.result.summary").value("1 alerts matched"))
                .andExpect(jsonPath("$.result.alerts[0].summary").value("orders sink DB failed"))
                .andExpect(jsonPath("$.result.alerts[1]").doesNotExist());
    }

    @Test
    void eventIncidentSummaryScopedByPipelineUsesPipelineEventQueryAndFiltersIncidents() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        PipelineEntity pipeline = pipeline(pipelineId, tenantId, "orders-cdc");
        pipeline.setSinkConnectorName("orders-sink");
        IncidentEntity target = incident("orders sink failed", "ERROR", "OPEN", "CONNECTOR");
        target.setTenantId(tenantId);
        target.setOpenedAt(Instant.now());
        target.setGroupingKey(IncidentGroupingKeys.connectorWorker("orders-sink"));
        IncidentEntity unrelated = incident("other connector failed", "ERROR", "OPEN", "CONNECTOR");
        unrelated.setTenantId(tenantId);
        unrelated.setOpenedAt(Instant.now());
        unrelated.setGroupingKey(IncidentGroupingKeys.connectorWorker("other-sink"));
        EventEntity warning = event(tenantId, EventLevel.WARN, "CONSUMER_LAG_WARNING", "consumer lag 경고");
        warning.setPipelineId(pipelineId);

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline));
        when(connectorRepository.findByPipelineId(pipelineId))
                .thenReturn(List.of(connector(pipelineId, "orders-sink", ConnectorKind.SINK)));
        when(incidentRepository.findScopedByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
                eq(tenantId), eq("OPEN"), eq(List.of("WARN", "ERROR", "CRITICAL")),
                any(Instant.class), any(), any()))
                .thenReturn(List.of(target));
        when(eventRepository.findByTenantIdAndPipelineIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(tenantId), eq(pipelineId), eq(List.of(EventLevel.WARN, EventLevel.ERROR)),
                any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(warning));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/events/summary", "proj-001")
                        .queryParam("pipeline_id", pipelineId.toString())
                        .queryParam("window", "2h")
                        .queryParam("level", "warn+")
                        .header("X-Request-Id", "req-events-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("analyze_event_log"))
                .andExpect(jsonPath("$.result.openIncidents").value(1))
                .andExpect(jsonPath("$.result.criticalIncidents").value(1))
                .andExpect(jsonPath("$.result.critical[0].title").value("orders sink failed"))
                .andExpect(jsonPath("$.result.critical[1]").doesNotExist())
                .andExpect(jsonPath("$.result.warnings[0].pipelineId").value(pipelineId.toString()))
                .andExpect(jsonPath("$.result.warnings[1]").doesNotExist());
    }

    @Test
    void eventIncidentSummaryScopedByConnectorUsesConnectorEventQuery() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        PipelineEntity pipeline = pipeline(pipelineId, tenantId, "orders-cdc");
        pipeline.setSinkDatasourceId(UUID.randomUUID());
        ConnectorEntity connector = connector(pipelineId, "orders-sink", ConnectorKind.SINK);
        IncidentEntity target = incident("orders sink failed", "ERROR", "OPEN", "CONNECTOR");
        target.setTenantId(tenantId);
        target.setOpenedAt(Instant.now());
        target.setGroupingKey(IncidentGroupingKeys.connectorWorker("orders-sink"));
        EventEntity warning = event(tenantId, EventLevel.ERROR, "CONNECTOR_TASK_FAILED",
                "'orders-cdc' 싱크 커넥터 task#0 실패");
        warning.setPipelineId(pipelineId);
        warning.setIncidentId(target.getId());

        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)).thenReturn(Optional.of(pipeline));
        when(incidentRepository.findScopedByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
                eq(tenantId), eq("OPEN"), eq(List.of("WARN", "ERROR", "CRITICAL")),
                any(Instant.class), any(), any()))
                .thenReturn(List.of(target));
        when(eventRepository.findConnectorScopedEventsOrderByCreatedAtDesc(
                eq(tenantId), eq(pipelineId), eq(List.of(EventLevel.WARN, EventLevel.ERROR)),
                any(Instant.class), eq(List.of(target.getId())), eq("orders-sink"),
                eq("connect-orders-sink"), any(Pageable.class)))
                .thenReturn(List.of(warning));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/events/summary", "proj-001")
                        .queryParam("connector_name", "orders-sink")
                        .queryParam("window", "2h")
                        .queryParam("level", "warn+")
                        .header("X-Request-Id", "req-events-connector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("analyze_event_log"))
                .andExpect(jsonPath("$.result.openIncidents").value(1))
                .andExpect(jsonPath("$.result.critical[0].title").value("orders sink failed"))
                .andExpect(jsonPath("$.result.warnings[0].type").value("CONNECTOR_TASK_FAILED"))
                .andExpect(jsonPath("$.result.warnings[1]").doesNotExist());
    }

    @Test
    void eventIncidentSummaryRejectsInvalidPipelineIdWithErrorEnvelope() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));

        mockMvc().perform(get("/internal/ops/projects/{projectId}/observability/events/summary", "proj-001")
                        .queryParam("pipeline_id", "not-a-uuid")
                        .header("X-Request-Id", "req-events-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.operation").value("analyze_event_log"))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listAlertsReturnsFastApiCompatibleErrorEnvelopeForUnknownProject() {
        String projectId = "missing-project";
        when(workspaceRepository.findByNamespace(projectId)).thenReturn(Optional.empty());

        ResponseEntity<OpsEnvelope<AlertListResult>> response =
                controller.listAlerts(projectId, null, null, null, null, null, new MockHttpServletRequest());

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
                controller.listAlerts("proj-001", null, null, "abc", null, null, new MockHttpServletRequest());

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
    void consumerLagReturnsPartitionOffsetsP95AndTopPartitions() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByCrName("orders-sink")).thenReturn(Optional.of(connector(pipelineId, "orders-sink")));
        when(pipelineRepository.findByIdAndTenantId(pipelineId, tenantId))
                .thenReturn(Optional.of(pipeline(pipelineId, tenantId, "orders-cdc")));

        TopicPartition tp0 = new TopicPartition("cdc.table.proj-001.orders", 0);
        TopicPartition tp1 = new TopicPartition("cdc.table.proj-001.orders", 1);
        ListConsumerGroupOffsetsResult offsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        when(adminClient.listConsumerGroupOffsets("connect-orders-sink")).thenReturn(offsetsResult);
        when(offsetsResult.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(Map.of(
                tp0, new OffsetAndMetadata(10L),
                tp1, new OffsetAndMetadata(20L))));

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(adminClient.listOffsets(any())).thenReturn(listOffsetsResult);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(Map.of(
                tp0, new ListOffsetsResult.ListOffsetsResultInfo(15L, -1L, Optional.empty()),
                tp1, new ListOffsetsResult.ListOffsetsResultInfo(55L, -1L, Optional.empty()))));

        ResponseEntity<OpsEnvelope<ConsumerLagResult>> response =
                controller.consumerLag("proj-001", "connect-orders-sink", new MockHttpServletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ConsumerLagResult result = response.getBody().result();
        assertThat(result.consumerGroup()).isEqualTo("connect-orders-sink");
        assertThat(result.totalLag()).isEqualTo(40L);
        assertThat(result.source()).isEqualTo("kafka-admin");
        assertThat(result.observedAt()).isNotNull();
        assertThat(result.partitions()).hasSize(2);
        assertThat(result.partitions().getFirst().currentOffset()).isEqualTo(10L);
        assertThat(result.partitions().getFirst().logEndOffset()).isEqualTo(15L);
        assertThat(result.p95Lag()).isEqualTo(35.0);
        assertThat(result.topLagPartitions()).hasSize(2);
        assertThat(result.topLagPartitions().getFirst().partition()).isEqualTo(1);
        assertThat(result.summary()).contains("lag p95=35.000");
        assertThat(result.summary()).contains("current committed offsets");
        assertThat(result.summary()).contains("offset position snapshot");
        assertThat(result.summary()).doesNotContain("offset progression");
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
    void searchLogsScopesPlainTextQueryToProjectKafkaConnectLogs() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId))
                .thenReturn(List.of(connector(UUID.randomUUID(), "orders-source", ConnectorKind.SOURCE),
                        connector(UUID.randomUUID(), "orders-sink", ConnectorKind.SINK)));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-logs-001");
        ResponseEntity<OpsEnvelope<com.bifrost.ops.internalops.dto.LogSearchResult>> response =
                controller.searchLogs("proj-001", java.util.Map.of("query", "pipeline events", "limit", 10), request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        assertThat(queryCaptor.getValue()).isEqualTo(
                "{namespace=\"platform-kafka\",app=\"kafka-connect\"}"
                        + " |~ \"cdc\\\\.table\\\\.proj-001\\\\.|eda\\\\.table\\\\.proj-001\\\\."
                        + "|bifrost\\\\.proj-001\\\\."
                        + "|(^|[^A-Za-z0-9._-])proj-proj-001-user([^A-Za-z0-9._-]|$)"
                        + "|(^|[^A-Za-z0-9._-])orders-source([^A-Za-z0-9._-]|$)"
                        + "|(^|[^A-Za-z0-9._-])connect-orders-source([^A-Za-z0-9._-]|$)"
                        + "|(^|[^A-Za-z0-9._-])orders-sink([^A-Za-z0-9._-]|$)"
                        + "|(^|[^A-Za-z0-9._-])connect-orders-sink([^A-Za-z0-9._-]|$)\""
                        + " |= \"pipeline events\"");
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
        assertThat(queryCaptor.getValue()).isEqualTo(
                "{namespace=\"platform-kafka\",app=\"kafka-connect\"}"
                        + " |~ \"cdc\\\\.table\\\\.proj-001\\\\.|eda\\\\.table\\\\.proj-001\\\\."
                        + "|bifrost\\\\.proj-001\\\\."
                        + "|(^|[^A-Za-z0-9._-])proj-proj-001-user([^A-Za-z0-9._-]|$)\""
                        + " |= \"pipeline events\"");
    }

    @Test
    void searchLogsDoesNotTrustCallerProvidedNamespaceSelector() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        controller.searchLogs(
                "proj-001",
                java.util.Map.of("query", "{namespace=~\"proj-001|other-project\",app=\"worker\",stream=\"stderr\"} |= \"error\"", "limit", 10),
                new MockHttpServletRequest());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        assertThat(queryCaptor.getValue()).isEqualTo(
                "{namespace=\"platform-kafka\",app=\"kafka-connect\",stream=\"stderr\"}"
                        + " |~ \"cdc\\\\.table\\\\.proj-001\\\\.|eda\\\\.table\\\\.proj-001\\\\."
                        + "|bifrost\\\\.proj-001\\\\."
                        + "|(^|[^A-Za-z0-9._-])proj-proj-001-user([^A-Za-z0-9._-]|$)\""
                        + " |= \"error\"");
    }

    @Test
    void searchLogsKeepsMandatoryProjectFilterWithCallerProvidedBroadSelector() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        controller.searchLogs(
                "proj-001",
                java.util.Map.of("query", "{job=~\".*\"} |= \"error\"", "limit", 10),
                new MockHttpServletRequest());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        assertThat(queryCaptor.getValue()).startsWith("{namespace=\"platform-kafka\",app=\"kafka-connect\",job=~\".*\"}");
        assertThat(queryCaptor.getValue()).contains(" |~ \"cdc\\\\.table\\\\.proj-001\\\\.");
        assertThat(queryCaptor.getValue()).contains("(^|[^A-Za-z0-9._-])proj-proj-001-user([^A-Za-z0-9._-]|$)");
        assertThat(queryCaptor.getValue()).endsWith(" |= \"error\"");
    }

    @Test
    void searchLogsLineFilterDoesNotMatchOverlappingProjectsOrConnectorNames() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj-001")).thenReturn(Optional.of(workspace(tenantId, "proj-001")));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId))
                .thenReturn(List.of(connector(UUID.randomUUID(), "abc-source", ConnectorKind.SOURCE)));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        controller.searchLogs("proj-001", java.util.Map.of("limit", 10), new MockHttpServletRequest());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        Pattern lineFilter = Pattern.compile(lineFilterRegex(queryCaptor.getValue()));
        assertThat(lineFilter.matcher("topic cdc.table.proj-001.orders").find()).isTrue();
        assertThat(lineFilter.matcher("topic cdc.table.proj-001-a.orders").find()).isFalse();
        assertThat(lineFilter.matcher("principal proj-proj-001-user connected").find()).isTrue();
        assertThat(lineFilter.matcher("principal proj-proj-001-user-extra connected").find()).isFalse();
        assertThat(lineFilter.matcher("connector abc-source failed").find()).isTrue();
        assertThat(lineFilter.matcher("connector abc-source-extra failed").find()).isFalse();
        assertThat(lineFilter.matcher("group connect-abc-source rebalanced").find()).isTrue();
        assertThat(lineFilter.matcher("group connect-abc-source-extra rebalanced").find()).isFalse();
    }

    @Test
    void searchLogsEscapesRegexScopeAndRawTextQuery() {
        UUID tenantId = UUID.randomUUID();
        when(workspaceRepository.findByNamespace("proj.001+blue"))
                .thenReturn(Optional.of(workspace(tenantId, "proj.001+blue")));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId))
                .thenReturn(List.of(connector(UUID.randomUUID(), "orders.sink+1", ConnectorKind.SINK)));
        when(lokiClient.queryRange(any(), anyLong(), anyLong(), eq(10))).thenReturn(List.of());

        controller.searchLogs(
                "proj.001+blue",
                java.util.Map.of("query", "path \"quoted\"\\tail", "limit", 10),
                new MockHttpServletRequest());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(lokiClient).queryRange(queryCaptor.capture(), anyLong(), anyLong(), eq(10));
        assertThat(queryCaptor.getValue()).contains("cdc\\\\.table\\\\.proj\\\\.001\\\\+blue\\\\.");
        assertThat(queryCaptor.getValue()).contains("orders\\\\.sink\\\\+1");
        assertThat(queryCaptor.getValue()).endsWith(" |= \"path \\\"quoted\\\"\\\\tail\"");
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

    private static String lineFilterRegex(String logQl) {
        String marker = " |~ \"";
        int start = logQl.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("missing line regex filter: " + logQl);
        }
        start += marker.length();
        int end = logQl.indexOf('"', start);
        if (end < 0) {
            throw new AssertionError("unterminated line regex filter: " + logQl);
        }
        return logQl.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
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
