package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.RecentChangesResult;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
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
    private final InternalOpsPipelineController controller =
            new InternalOpsPipelineController(workspaceRepository, pipelineRepository, connectorRepository);

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

        mockMvc().perform(get("/internal/ops/projects/{projectId}/pipelines/changes", PROJECT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.operation").value("get_recent_changes"))
                .andExpect(jsonPath("$.result.changes[0].changeId").isNotEmpty())
                .andExpect(jsonPath("$.result.changes[0].type").value("STATUS_CHANGE"))
                .andExpect(jsonPath("$.result.changes[0].description").isNotEmpty())
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

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-changes-001");
        return request;
    }
}
