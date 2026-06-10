package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.pipeline.dto.SyncStatusResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineSyncServiceTest {

    @Mock PipelineRepository pipelineRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock SecretStore secretStore;
    @Mock DynamicDataSourceFactory dataSourceFactory;
    @Mock WorkspaceAccessGuard accessGuard;

    @InjectMocks PipelineSyncService syncService;

    @Test
    void edaSyncStatusReturnsNotApplicable() {
        UUID wsId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "test@example.com");

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setPattern(PipelinePattern.FAN_OUT);

        doNothing().when(accessGuard).requireAccess(any(), any());
        when(pipelineRepository.findByIdAndTenantId(pipelineId, wsId)).thenReturn(Optional.of(pipeline));

        SyncStatusResponse result = syncService.syncStatus(wsId, principal, pipelineId);

        assertThat(result.applicable()).isFalse();
        assertThat(result.sourceRows()).isEqualTo(-1);
        assertThat(result.sinkRows()).isEqualTo(-1);
        assertThat(result.delta()).isEqualTo(-1);
    }
}
