package com.bifrost.ops.provisioning.persistence;

import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * connector 상태 → connectors 행 반영 adapter 검증(#46). DB 없이 repository를 mock해
 * 갱신 필드와 행 부재 시 동작(no-op)을 확인한다.
 */
class ConnectorStatusRepositoryAdapterTest {

    private final ConnectorRepository repo = mock(ConnectorRepository.class);
    private final ConnectorStatusRepositoryAdapter adapter = new ConnectorStatusRepositoryAdapter(repo);

    private ConnectorEntity existing(String crName) {
        ConnectorEntity e = new ConnectorEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(UUID.randomUUID());
        e.setCrName(crName);
        e.setKind(ConnectorKind.SOURCE);
        e.setConnectorClass("io.debezium.connector.postgresql.PostgresConnector");
        e.setTasksMax(1);
        return e;
    }

    @Test
    void updatesStateAndErrorForExistingRow() {
        ConnectorEntity entity = existing("pipe-source");
        when(repo.findByCrName("pipe-source")).thenReturn(Optional.of(entity));

        adapter.record(new ConnectorStatusUpdate(
                "pipe-source", ConnectorRuntimeState.PARTIALLY_FAILED, PipelineLifecycle.LAG,
                3, 1, "task 1 failed: connection reset"));

        ArgumentCaptor<ConnectorEntity> saved = ArgumentCaptor.forClass(ConnectorEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo("PARTIALLY_FAILED");
        assertThat(saved.getValue().getLastError()).contains("connection reset");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void skipsWhenRowMissing() {
        when(repo.findByCrName("ghost")).thenReturn(Optional.empty());

        adapter.record(new ConnectorStatusUpdate(
                "ghost", ConnectorRuntimeState.RUNNING, PipelineLifecycle.ACTIVE, 1, 0, null));

        verify(repo, never()).save(any());
    }
}
