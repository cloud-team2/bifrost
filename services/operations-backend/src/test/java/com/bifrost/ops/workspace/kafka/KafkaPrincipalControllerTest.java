package com.bifrost.ops.workspace.kafka;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalCreateRequest;
import com.bifrost.ops.workspace.kafka.dto.KafkaPrincipalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaPrincipalControllerTest {

    private final KafkaPrincipalService service = mock(KafkaPrincipalService.class);
    private final KafkaPrincipalController controller = new KafkaPrincipalController(service);
    private final UUID wsId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "admin@bifrost.io");

    @Test
    void listDelegates() {
        when(service.list(wsId, principal)).thenReturn(List.of(sample("ACTIVE")));

        var out = controller.list(wsId, principal);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).username()).isEqualTo("team");
    }

    @Test
    void createReturns201() {
        KafkaPrincipalCreateRequest req = new KafkaPrincipalCreateRequest("team", "secret/team");
        when(service.create(wsId, principal, req)).thenReturn(sample("ACTIVE"));

        ResponseEntity<KafkaPrincipalResponse> out = controller.create(wsId, principal, req);

        assertThat(out.getStatusCode().value()).isEqualTo(201);
        assertThat(out.getBody()).isNotNull();
        assertThat(out.getBody().status()).isEqualTo("ACTIVE");
    }

    @Test
    void lifecycleEndpointsDelegate() {
        when(service.deactivate(wsId, principal, id)).thenReturn(sample("INACTIVE"));
        when(service.revoke(wsId, principal, id)).thenReturn(sample("REVOKED"));
        when(service.rotate(wsId, principal, id)).thenReturn(sample("ACTIVE"));

        assertThat(controller.deactivate(wsId, id, principal).status()).isEqualTo("INACTIVE");
        assertThat(controller.revoke(wsId, id, principal).status()).isEqualTo("REVOKED");
        assertThat(controller.rotate(wsId, id, principal).status()).isEqualTo("ACTIVE");
        verify(service).deactivate(wsId, principal, id);
        verify(service).revoke(wsId, principal, id);
        verify(service).rotate(wsId, principal, id);
    }

    private KafkaPrincipalResponse sample(String status) {
        return new KafkaPrincipalResponse(id, wsId, "team", "secret/team", status, Instant.now(), null, null);
    }
}
