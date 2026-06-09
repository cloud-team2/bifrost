package com.bifrost.ops.pipeline.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.service.PipelineService;
import com.bifrost.ops.pipeline.service.PipelineSyncService;
import com.bifrost.ops.pipeline.runtime.PipelineRuntimeMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineControllerTest {

    private final PipelineService service = mock(PipelineService.class);
    private final PipelineSyncService syncService = mock(PipelineSyncService.class);
    private final com.bifrost.ops.pipeline.service.PipelineTopicService topicService =
            mock(com.bifrost.ops.pipeline.service.PipelineTopicService.class);
    private final com.bifrost.ops.pipeline.service.PipelineMessageService messageService =
            mock(com.bifrost.ops.pipeline.service.PipelineMessageService.class);
    private final PipelineRuntimeMetadataService runtimeMetadataService =
            mock(PipelineRuntimeMetadataService.class);
    private final PipelineController controller =
            new PipelineController(service, syncService, topicService, messageService, runtimeMetadataService);

    private final UUID wsId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

    @Test
    void createReturns201AndCreatingStatus() {
        PipelineCreateRequest req = new PipelineCreateRequest("orders", "fan-out", UUID.randomUUID(), null, "public", "orders");
        when(service.create(eq(wsId), eq(principal), any())).thenReturn(sample("creating"));

        ResponseEntity<PipelineResponse> resp = controller.create(wsId, principal, req);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("creating");
    }

    @Test
    void listDelegatesWithStatusFilter() {
        when(service.list(wsId, principal, "active")).thenReturn(List.of(sample("active")));
        assertThat(controller.list(wsId, principal, "active")).hasSize(1);
    }

    @Test
    void getDelegates() {
        UUID id = UUID.randomUUID();
        when(service.get(wsId, principal, id)).thenReturn(sample("active"));
        assertThat(controller.get(wsId, id, principal).status()).isEqualTo("active");
    }

    @Test
    void deleteReturns204() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> resp = controller.delete(wsId, id, principal, false);
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(service).delete(wsId, principal, id, false);
    }

    private static PipelineResponse sample(String status) {
        return new PipelineResponse(UUID.randomUUID(), "orders", "fan-out", status, null,
                UUID.randomUUID(), null, "public", "orders", "cdc.table.team-a.db.public.orders",
                "src", null, Instant.now());
    }
}
