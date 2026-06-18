package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.governance.audit.persistence.entity.AuditEventEntity;
import com.bifrost.ops.governance.audit.persistence.repository.AuditEventRepository;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.governance.changemanagement.persistence.repository.ChangeTicketRepository;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.RecentChangesResult;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.admin.AdminClient;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalOpsPipelineControllerTest {

    private static final String PROJECT_ID = "proj-001";

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final PipelineRepository pipelineRepository = mock(PipelineRepository.class);
    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final AdminClient adminClient = mock(AdminClient.class);
    private final ChangeTicketRepository changeTicketRepository = mock(ChangeTicketRepository.class);
    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final KubernetesClient k8s = mock(KubernetesClient.class);
    private final InternalOpsPipelineController controller =
            new InternalOpsPipelineController(
                    workspaceRepository,
                    pipelineRepository,
                    connectorRepository,
                    adminClient,
                    changeTicketRepository,
                    auditEventRepository,
                    k8s,
                    "platform-kafka",
                    "platform-connect");

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void changesSynthesizesCreationAndStatusEventsNewestFirst() {
        WorkspaceEntity workspace = workspace();
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace));

        Instant created = Instant.parse("2026-06-01T00:00:00Z");
        Instant statusChanged = Instant.parse("2026-06-05T12:00:00Z");
        PipelineEntity pipeline = pipeline("orders", created, statusChanged,
                PipelineLifecycle.LAG, "consumer lag 임계 초과");
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(pipeline));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId)).thenReturn(List.of());
        when(changeTicketRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        when(auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());

        ResponseEntity<OpsEnvelope<RecentChangesResult>> response =
                controller.changes(PROJECT_ID, null, request());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().operation()).isEqualTo("get_recent_changes");

        List<RecentChangesResult.Change> changes = response.getBody().result().changes();
        // 최신(상태 전이) → 과거(생성) 순서
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).type()).isEqualTo("STATUS_CHANGE");
        assertThat(changes.get(0).changedAt()).isEqualTo(statusChanged);
        assertThat(changes.get(0).description()).contains("LAG").contains("consumer lag 임계 초과");
        assertThat(changes.get(1).type()).isEqualTo("PIPELINE_CREATED");
        assertThat(changes.get(1).changedAt()).isEqualTo(created);
        assertThat(changes.get(1).description()).contains("orders");
        assertThat(response.getBody().result().summary()).contains("live change evidence");
        assertThat(changes).allSatisfy(c -> {
            assertThat(c.changeId()).isNotBlank();
            assertThat(c.type()).isNotBlank();
            assertThat(c.description()).isNotBlank();
        });
    }

    @Test
    void changesWithoutStatusTransitionEmitsOnlyCreationEvent() {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        Instant created = Instant.parse("2026-06-01T00:00:00Z");
        // statusUpdatedAt == createdAt → 상태 전이 이벤트 없음
        PipelineEntity pipeline = pipeline("orders", created, created, PipelineLifecycle.CREATING, null);
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(pipeline));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId)).thenReturn(List.of());
        when(changeTicketRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        when(auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());

        ResponseEntity<OpsEnvelope<RecentChangesResult>> response =
                controller.changes(PROJECT_ID, null, request());

        List<RecentChangesResult.Change> changes = response.getBody().result().changes();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).type()).isEqualTo("PIPELINE_CREATED");
    }

    @Test
    void changesAppliesLimit() {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        PipelineEntity a = pipeline("a", base, base.plusSeconds(3600), PipelineLifecycle.ACTIVE, "ok");
        PipelineEntity b = pipeline("b", base.plusSeconds(10), base.plusSeconds(7200), PipelineLifecycle.ERROR, "fail");
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(b, a));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId)).thenReturn(List.of());
        when(changeTicketRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        when(auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());

        ResponseEntity<OpsEnvelope<RecentChangesResult>> response =
                controller.changes(PROJECT_ID, 2, request());

        List<RecentChangesResult.Change> changes = response.getBody().result().changes();
        assertThat(changes).hasSize(2);
        // 전체 4건 중 changedAt 상위 2건(상태 전이 7200, 3600)만
        assertThat(changes).allMatch(c -> "STATUS_CHANGE".equals(c.type()));
        assertThat(changes.get(0).changedAt()).isEqualTo(base.plusSeconds(7200));
    }

    @Test
    void changesUnknownProjectThrows() {
        when(workspaceRepository.findByNamespace("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.changes("missing", null, request()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void changesIncludesLiveConnectorTicketAndAuditSourcesWithoutSecretValues() {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        ConnectorEntity connector = connector("orders-source", ConnectorKind.SOURCE,
                Instant.parse("2026-06-03T00:00:00Z"));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId)).thenReturn(List.of(connector));

        ChangeTicketEntity ticket = new ChangeTicketEntity();
        ticket.setId(UUID.fromString("00000000-0000-0000-0000-000000000111"));
        ticket.setTenantId(tenantId);
        ticket.setTitle("rotate connector credential config");
        ticket.setScopeOperation("update_connector");
        ticket.setStatus("APPROVED");
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.parse("2026-06-04T00:00:00Z"));
        when(changeTicketRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(ticket));

        AuditEventEntity audit = new AuditEventEntity();
        audit.setId(UUID.fromString("00000000-0000-0000-0000-000000000222"));
        audit.setTenantId(tenantId);
        audit.setAction("update_connector");
        audit.setTargetType("CONNECTOR");
        audit.setDetail("config changed password=super-secret credential rotation");
        ReflectionTestUtils.setField(audit, "createdAt", Instant.parse("2026-06-05T00:00:00Z"));
        when(auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(audit));

        RecentChangesResult result = controller.changes(PROJECT_ID, null, request()).getBody().result();

        assertThat(result.changes()).extracting(RecentChangesResult.Change::type)
                .contains("CONNECTOR_CONFIG_CREATED", "CHANGE_TICKET", "AUDIT_EVENT");
        assertThat(result.summary())
                .contains("live change evidence")
                .contains("최근 pipeline/connector config 변경 evidence");
        assertThat(result.changes()).extracting(RecentChangesResult.Change::description)
                .anySatisfy(description -> assertThat(description)
                        .contains("update_connector")
                        .doesNotContain("super-secret")
                        .doesNotContain("credential"));
    }

    @Test
    void changesSurfacesUnavailableKubernetesRuntimeSource() {
        InternalOpsPipelineController noK8sController =
                new InternalOpsPipelineController(
                        workspaceRepository,
                        pipelineRepository,
                        connectorRepository,
                        adminClient,
                        changeTicketRepository,
                        auditEventRepository,
                        null,
                        "platform-kafka",
                        "platform-connect");

        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId)).thenReturn(List.of());
        when(changeTicketRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        when(auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());

        RecentChangesResult result = noK8sController.changes(PROJECT_ID, null, request()).getBody().result();

        assertThat(result.changes()).isEmpty();
        assertThat(result.unavailableSources())
                .contains("kubernetes:strimzi-runtime unavailable (client not configured)");
        assertThat(result.summary())
                .contains("0 changes matched")
                .contains("unavailable live sources");
    }

    @Test
    void recentChangesDoesNotCountRuntimeSnapshotsAsConfigChangeEvidence() {
        RecentChangesResult result = RecentChangesResult.of(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new RecentChangesResult.Change(
                        "orders-source:kafkaconnector-config",
                        "CONNECTOR_CONFIG_SNAPSHOT",
                        "KafkaConnector CR config snapshot for connector 'orders-source'",
                        Instant.parse("2026-06-06T00:00:00Z"))),
                null);

        assertThat(result.summary())
                .contains("CONNECTOR_CONFIG_SNAPSHOT")
                .doesNotContain("최근 pipeline/connector config 변경 evidence count");
    }

    @Test
    void listPipelineStatusesIncludesRealPipelineRowsAndLeavesLagEmptyOnKafkaFailure() throws Exception {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        Instant created = Instant.parse("2026-06-01T00:00:00Z");
        PipelineEntity pipeline = pipeline("orders", created, created, PipelineLifecycle.ACTIVE, null);
        pipeline.setSinkConnectorName("orders-sink");
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(pipeline));
        when(connectorRepository.findByPipelineId(pipeline.getId())).thenReturn(List.of());

        mockMvc().perform(get("/internal/ops/projects/{projectId}/pipelines/status", PROJECT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("list_pipelines"))
                .andExpect(jsonPath("$.result.pipelines[0].id").value(pipeline.getId().toString()))
                .andExpect(jsonPath("$.result.pipelines[0].name").value("orders"))
                .andExpect(jsonPath("$.result.pipelines[0].status").value("active"))
                .andExpect(jsonPath("$.result.pipelines[0].lag").doesNotExist())
                .andExpect(jsonPath("$.result.pipelines[0].error").isNotEmpty());
    }

    @Test
    void listPipelineStatusesReturnsEnvelopeForUnknownProject() throws Exception {
        when(workspaceRepository.findByNamespace("missing")).thenReturn(Optional.empty());

        mockMvc().perform(get("/internal/ops/projects/{projectId}/pipelines/status", "missing")
                        .header("X-Request-Id", "req-pipelines-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.request_id").value("req-pipelines-404"))
                .andExpect(jsonPath("$.operation").value("list_pipelines"))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    /** ai-service DeploymentsData 계약: result.changes[].{changeId,type,description,changedAt} camelCase + ISO 날짜. */
    @Test
    void changesJsonMatchesAiServiceContract() throws Exception {
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace()));
        Instant created = Instant.parse("2026-06-01T00:00:00Z");
        Instant statusChanged = Instant.parse("2026-06-05T12:00:00Z");
        PipelineEntity pipeline = pipeline("orders", created, statusChanged,
                PipelineLifecycle.LAG, "lag");
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(pipeline));
        when(connectorRepository.findByTenantIdOrderByCrName(tenantId)).thenReturn(List.of());
        when(changeTicketRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        when(auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());

        mockMvc().perform(get("/internal/ops/projects/{projectId}/pipelines/changes", PROJECT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.operation").value("get_recent_changes"))
                .andExpect(jsonPath("$.result.changes[0].changeId").isNotEmpty())
                .andExpect(jsonPath("$.result.changes[0].type").value("STATUS_CHANGE"))
                .andExpect(jsonPath("$.result.changes[0].description").isNotEmpty())
                .andExpect(jsonPath("$.result.summary").value(org.hamcrest.Matchers.containsString("live change evidence")))
                .andExpect(jsonPath("$.result.changes[0].changedAt").value("2026-06-05T12:00:00Z"))
                .andExpect(jsonPath("$.result.changes[1].changedAt").value("2026-06-01T00:00:00Z"));
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(tenantId);
        workspace.setName("Demo");
        workspace.setNamespace(PROJECT_ID);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }

    private PipelineEntity pipeline(String name, Instant createdAt, Instant statusUpdatedAt,
                                    PipelineLifecycle status, String statusMessage) {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setTenantId(tenantId);
        pipeline.setName(name);
        pipeline.setPattern(PipelinePattern.DIRECT);
        pipeline.setTopicName(name + ".cdc");
        pipeline.setStatus(status);
        pipeline.setStatusMessage(statusMessage);
        pipeline.setStatusUpdatedAt(statusUpdatedAt);
        // createdAt 은 @PrePersist 로 채워지므로 테스트에서는 reflection 으로 주입
        ReflectionTestUtils.setField(pipeline, "createdAt", createdAt);
        return pipeline;
    }

    private ConnectorEntity connector(String name, ConnectorKind kind, Instant createdAt) {
        ConnectorEntity connector = new ConnectorEntity();
        connector.setId(UUID.randomUUID());
        connector.setPipelineId(UUID.randomUUID());
        connector.setCrName(name);
        connector.setKind(kind);
        connector.setConnectorClass("io.debezium.connector.postgresql.PostgresConnector");
        connector.setTasksMax(1);
        connector.setState("RUNNING");
        connector.setCreatedAt(createdAt);
        return connector;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-changes-001");
        return request;
    }
}
