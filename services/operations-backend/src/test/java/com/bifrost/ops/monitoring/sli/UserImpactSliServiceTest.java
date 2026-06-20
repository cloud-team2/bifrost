package com.bifrost.ops.monitoring.sli;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.monitoring.dto.SliDefinitionResponse;
import com.bifrost.ops.monitoring.dto.SliMeasurementResponse;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserImpactSliServiceTest {

    private final PrometheusClient prometheusClient = mock(PrometheusClient.class);
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final PipelineRepository pipelineRepository = mock(PipelineRepository.class);
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void definitionsExposeGoodEventAndTotalEventForAllUserImpactSlis() {
        UserImpactSliService service = service(false);

        List<SliDefinitionResponse> definitions = service.definitions();

        assertThat(definitions).hasSize(UserImpactSliType.values().length);
        assertThat(definitions)
                .extracting(SliDefinitionResponse::type)
                .containsExactly(UserImpactSliType.values());
        assertThat(definitions.getFirst().goodEvent()).isNotBlank();
        assertThat(definitions.getFirst().totalEvent()).isNotBlank();
    }

    @Test
    void provisioningSuccessUsesPipelineLifecycleCountsFromDatabase() {
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace()));
        when(pipelineRepository.countByTenantId(workspaceId)).thenReturn(10L);
        when(pipelineRepository.countByTenantIdAndStatus(workspaceId, PipelineLifecycle.ACTIVE)).thenReturn(9L);

        SliMeasurementResponse response = service(false).measurement(
                workspaceId, UserImpactSliType.PROVISIONING_SUCCESS_RATE, 60);

        assertThat(response.type()).isEqualTo(UserImpactSliType.PROVISIONING_SUCCESS_RATE);
        assertThat(response.goodEvents()).isEqualTo(9.0);
        assertThat(response.totalEvents()).isEqualTo(10.0);
        assertThat(response.sliRatio()).isEqualTo(0.9);
        assertThat(response.status()).isEqualTo(UserImpactSliStatus.CRITICAL);
        assertThat(response.source()).isEqualTo("database");
        assertThat(response.windowMinutes()).isEqualTo(60);
        verify(pipelineRepository).countByTenantId(workspaceId);
        verify(pipelineRepository).countByTenantIdAndStatus(workspaceId, PipelineLifecycle.ACTIVE);
    }

    @Test
    void prometheusDisabledReturnsUnknownForMetricBackedSli() {
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace()));

        SliMeasurementResponse response = service(false).measurement(
                workspaceId, UserImpactSliType.DATA_FRESHNESS, 30);

        assertThat(response.status()).isEqualTo(UserImpactSliStatus.UNKNOWN);
        assertThat(response.sliRatio()).isNull();
        assertThat(response.note()).contains("Prometheus 비활성화");
    }

    @Test
    void processingSuccessSubtractsFailedEventsFromTotalEvents() {
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace()));
        when(prometheusClient.queryScalar(anyString())).thenReturn(100.0, 2.0);

        SliMeasurementResponse response = service(true).measurement(
                workspaceId, UserImpactSliType.PROCESSING_SUCCESS_RATE, 30);

        assertThat(response.goodEvents()).isEqualTo(98.0);
        assertThat(response.totalEvents()).isEqualTo(100.0);
        assertThat(response.sliRatio()).isEqualTo(0.98);
        assertThat(response.status()).isEqualTo(UserImpactSliStatus.CRITICAL);
        assertThat(response.source()).isEqualTo("prometheus");
    }

    @Test
    void prometheusFailureFallsBackToUnknownMeasurement() {
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace()));
        when(prometheusClient.queryScalar(anyString())).thenThrow(new RestClientException("connection refused"));

        SliMeasurementResponse response = service(true).measurement(
                workspaceId, UserImpactSliType.DATA_COMPLETENESS, 30);

        assertThat(response.status()).isEqualTo(UserImpactSliStatus.UNKNOWN);
        assertThat(response.sliRatio()).isNull();
        assertThat(response.note()).contains("Prometheus 조회 실패");
    }

    private UserImpactSliService service(boolean prometheusEnabled) {
        return new UserImpactSliService(
                prometheusEnabled,
                "platform-kafka",
                prometheusClient,
                workspaceRepository,
                pipelineRepository);
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setName("Payments");
        workspace.setNamespace("payments");
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }
}
