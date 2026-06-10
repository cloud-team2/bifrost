package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.dto.PipelineStageStatusResponse;
import com.bifrost.ops.pipeline.kafka.OffsetSnapshotStore;
import com.bifrost.ops.pipeline.kafka.RateResult;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineTopicServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private WorkspaceAccessGuard accessGuard;
    @Mock private OffsetSnapshotStore snapshotStore;
    @Mock private KafkaMetricsQuery kafkaMetricsQuery;

    private PipelineTopicService service() {
        return new PipelineTopicService(pipelineRepository, connectorRepository, accessGuard,
                mock(AdminClient.class), snapshotStore, kafkaMetricsQuery);
    }

    @Test
    void stageStatusAttributesSinkFailureAsBottleneckWithSinkLag() {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.DIRECT);   // CDC → source + sink 단계
        p.setStatus(PipelineLifecycle.ERROR);
        p.setTopicName("pipe.orders");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));

        when(connectorRepository.findByPipelineId(id)).thenReturn(List.of(
                connector(ConnectorKind.SOURCE, "RUNNING", null),
                connector(ConnectorKind.SINK, "FAILED", "PSQLException: relation does not exist")));

        // Prometheus 비활성 → source delay 빈 결과(null), sink lag는 snapshot 사용
        lenient().when(kafkaMetricsQuery.isEnabled()).thenReturn(false);
        lenient().when(snapshotStore.getRates("pipe.orders")).thenReturn(new RateResult(0.0, 0.0, 42L));

        PipelineStageStatusResponse res = service().stageStatus(wsId, principal, id);

        assertThat(res.overall()).isEqualTo("error");
        assertThat(res.bottleneck()).isEqualTo("SINK");
        assertThat(res.stages()).hasSize(2);

        PipelineStageStatusResponse.StageStatus source = res.stages().get(0);
        assertThat(source.stage()).isEqualTo("SOURCE");
        assertThat(source.connectorState()).isEqualTo("RUNNING");
        assertThat(source.status()).isEqualTo("OK");
        assertThat(source.delayMs()).isNull();

        PipelineStageStatusResponse.StageStatus sink = res.stages().get(1);
        assertThat(sink.stage()).isEqualTo("SINK");
        assertThat(sink.status()).isEqualTo("FAILED");
        assertThat(sink.error()).contains("PSQLException");
        assertThat(sink.lagMessages()).isEqualTo(42L);
    }

    private static ConnectorEntity connector(ConnectorKind kind, String state, String lastError) {
        ConnectorEntity c = new ConnectorEntity();
        c.setKind(kind);
        c.setState(state);
        c.setLastError(lastError);
        return c;
    }
}
