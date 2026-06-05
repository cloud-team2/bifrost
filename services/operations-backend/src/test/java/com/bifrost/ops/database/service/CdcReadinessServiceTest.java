package com.bifrost.ops.database.service;

import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.inspector.DatabaseInspectorFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CdcReadinessServiceTest {

    private final DatasourceRepository repo = mock(DatasourceRepository.class);
    private final SecretStore secretStore = mock(SecretStore.class);
    private final DatabaseInspectorFactory inspectorFactory = mock(DatabaseInspectorFactory.class);
    private final DatabaseInspector inspector = mock(DatabaseInspector.class);
    private final CdcReadinessService service = new CdcReadinessService(
            repo, secretStore, inspectorFactory, new ObjectMapper());

    private final UUID ws = UUID.randomUUID();

    @Test
    void aggregatesWorstStatusAndPersists() throws Exception {
        DatasourceEntity e = entity();
        wireInspector(e);
        when(inspector.checkSourceReadiness()).thenReturn(new CdcReadinessResponse(
                CdcReadinessStatus.BLOCKED, List.of(
                CdcCheck.ok("Max WAL Senders", "10", "> 0"),
                CdcCheck.of("WAL Level", CdcReadinessStatus.BLOCKED, "replica", "logical", "set logical"),
                CdcCheck.of("Publication", CdcReadinessStatus.WARNING, "없음", "존재/생성 가능", "grant"))));

        CdcReadinessResponse resp = service.check(ws, e.getId());

        assertThat(resp.overallStatus()).isEqualTo(CdcReadinessStatus.BLOCKED);
        assertThat(resp.checks()).hasSize(3);

        ArgumentCaptor<DatasourceEntity> saved = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getCdcReadinessStatus()).isEqualTo("BLOCKED");
        assertThat(saved.getValue().getCdcReadinessReport()).contains("WAL Level");
        assertThat(saved.getValue().getLastInspectedAt()).isNotNull();
    }

    @Test
    void okWhenAllChecksPass() throws Exception {
        DatasourceEntity e = entity();
        wireInspector(e);
        when(inspector.checkSourceReadiness()).thenReturn(new CdcReadinessResponse(
                CdcReadinessStatus.OK, List.of(
                CdcCheck.ok("WAL Level", "logical", "logical"),
                CdcCheck.ok("Max WAL Senders", "10", "> 0"))));

        assertThat(service.check(ws, e.getId()).overallStatus()).isEqualTo(CdcReadinessStatus.OK);
    }

    @Test
    void throwsNotFoundWhenMissing() {
        UUID dbId = UUID.randomUUID();
        when(repo.findByIdAndTenantId(dbId, ws)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.check(ws, dbId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.DATABASE_NOT_FOUND));
    }

    @Test
    void mapsConnectionFailureToDatabaseConnectionFailed() {
        DatasourceEntity e = entity();
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(secretStore.resolve("ref")).thenReturn(new DbCredential("user", "pw"));
        when(inspectorFactory.create(any(), any())).thenThrow(new RuntimeException("refused"));

        assertThatThrownBy(() -> service.check(ws, e.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.DATABASE_CONNECTION_FAILED));
    }

    @Test
    void sinkCheckPersistsSinkStatus() throws Exception {
        DatasourceEntity e = entity();
        wireInspector(e);
        when(inspector.checkSinkReadiness()).thenReturn(new CdcReadinessResponse(
                CdcReadinessStatus.OK, List.of(
                CdcCheck.ok("INSERT 권한", "true", "true"))));

        CdcReadinessResponse resp = service.checkSink(ws, e.getId());

        assertThat(resp.overallStatus()).isEqualTo(CdcReadinessStatus.OK);
        ArgumentCaptor<DatasourceEntity> saved = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getSinkReadinessStatus()).isEqualTo("OK");
    }

    @Test
    void worstPicksMostSevere() {
        assertThat(CdcReadinessStatus.worst(List.of(
                CdcReadinessStatus.OK, CdcReadinessStatus.WARNING, CdcReadinessStatus.OK)))
                .isEqualTo(CdcReadinessStatus.WARNING);
        assertThat(CdcReadinessStatus.worst(List.of())).isEqualTo(CdcReadinessStatus.OK);
    }

    private void wireInspector(DatasourceEntity e) throws Exception {
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(secretStore.resolve("ref")).thenReturn(new DbCredential("user", "pw"));
        when(inspectorFactory.create(any(), any())).thenReturn(inspector);
    }

    private DatasourceEntity entity() {
        DatasourceEntity e = new DatasourceEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(ws);
        e.setName("orders");
        e.setDbType(DbType.POSTGRESQL);
        e.setHost("h");
        e.setPort(5432);
        e.setDbName("app");
        e.setUsername("user");
        e.setSecretRef("ref");
        return e;
    }
}
