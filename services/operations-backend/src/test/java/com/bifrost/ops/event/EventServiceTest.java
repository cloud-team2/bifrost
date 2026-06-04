package com.bifrost.ops.event;

import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository repository;

    private final UUID tenant = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();

    private EventService service() {
        return new EventService(repository);
    }

    @Test
    void recordPersistsEvent() {
        service().record(tenant, pipelineId, EventLevel.ERROR, "PIPELINE_CREATE_FAILED", "boom");

        ArgumentCaptor<EventEntity> captor = ArgumentCaptor.forClass(EventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(captor.getValue().getLevel()).isEqualTo(EventLevel.ERROR);
        assertThat(captor.getValue().getType()).isEqualTo("PIPELINE_CREATE_FAILED");
    }

    @Test
    void listUsesCombinedFilterWhenBothPresent() {
        when(repository.findByTenantIdAndLevelAndPipelineIdOrderByCreatedAtDesc(tenant, EventLevel.WARN, pipelineId))
                .thenReturn(List.of(event(EventLevel.WARN)));
        assertThat(service().list(tenant, EventLevel.WARN, pipelineId)).hasSize(1);
    }

    @Test
    void listUsesPlainTenantQueryWhenNoFilter() {
        when(repository.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(List.of(event(EventLevel.INFO)));
        assertThat(service().list(tenant, null, null)).hasSize(1);
    }

    private static EventEntity event(EventLevel level) {
        EventEntity e = new EventEntity();
        e.setId(UUID.randomUUID());
        e.setLevel(level);
        e.setType("X");
        return e;
    }
}
