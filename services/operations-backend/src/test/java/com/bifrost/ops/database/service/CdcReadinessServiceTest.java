package com.bifrost.ops.database.service;

import com.bifrost.ops.database.cdc.CdcReadinessChecker;
import com.bifrost.ops.database.cdc.CdcReadinessCheckerRegistry;
import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CDC 준비도 오케스트레이션(#29). 동적 연결을 목으로 대체해 집계·영속·오류 경로를 검증한다.
 */
class CdcReadinessServiceTest {

    private final DatasourceRepository repo = mock(DatasourceRepository.class);
    private final SecretStore secretStore = mock(SecretStore.class);
    private final CdcReadinessCheckerRegistry registry = mock(CdcReadinessCheckerRegistry.class);
    private final DynamicDataSourceFactory dataSourceFactory = mock(DynamicDataSourceFactory.class);
    private final CdcReadinessChecker checker = mock(CdcReadinessChecker.class);
    private final CdcReadinessService service = new CdcReadinessService(
            repo, secretStore, registry, dataSourceFactory, new ObjectMapper());

    private final UUID ws = UUID.randomUUID();

    @Test
    void aggregatesWorstStatusAndPersists() throws Exception {
        DatasourceEntity e = entity();
        wireConnection(e);
        when(checker.check(any(Connection.class))).thenReturn(List.of(
                CdcCheck.ok("Max WAL Senders", "10", "> 0"),
                CdcCheck.of("WAL Level", CdcReadinessStatus.BLOCKED, "replica", "logical", "set logical"),
                CdcCheck.of("Publication", CdcReadinessStatus.WARNING, "없음", "존재/생성 가능", "grant create")));

        CdcReadinessResponse resp = service.check(ws, e.getId());

        // 가장 심각한 BLOCKED가 overall
        assertThat(resp.overallStatus()).isEqualTo(CdcReadinessStatus.BLOCKED);
        assertThat(resp.checks()).hasSize(3);

        // entity에 상태·리포트·시각 반영 후 저장
        ArgumentCaptor<DatasourceEntity> saved = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getCdcReadinessStatus()).isEqualTo("BLOCKED");
        assertThat(saved.getValue().getCdcReadinessReport()).contains("WAL Level");
        assertThat(saved.getValue().getLastInspectedAt()).isNotNull();
    }

    @Test
    void okWhenAllChecksPass() throws Exception {
        DatasourceEntity e = entity();
        wireConnection(e);
        when(checker.check(any(Connection.class))).thenReturn(List.of(
                CdcCheck.ok("WAL Level", "logical", "logical"),
                CdcCheck.ok("Max WAL Senders", "10", "> 0")));

        assertThat(service.check(ws, e.getId()).overallStatus()).isEqualTo(CdcReadinessStatus.OK);
    }

    @Test
    void throwsNotFoundWhenMissing() {
        UUID dbId = UUID.randomUUID();
        when(repo.findByIdAndTenantId(dbId, ws)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.check(ws, dbId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.DATABASE_NOT_FOUND));
    }

    @Test
    void mapsConnectionFailureToDatabaseConnectionFailed() {
        DatasourceEntity e = entity();
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(secretStore.resolve("ref")).thenReturn(new DbCredential("user", "pw"));
        when(registry.forEngine(DbType.POSTGRESQL)).thenReturn(checker);
        when(dataSourceFactory.create(any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.check(ws, e.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.DATABASE_CONNECTION_FAILED));
    }

    @Test
    void worstPicksMostSevere() {
        assertThat(CdcReadinessStatus.worst(List.of(
                CdcReadinessStatus.OK, CdcReadinessStatus.WARNING, CdcReadinessStatus.OK)))
                .isEqualTo(CdcReadinessStatus.WARNING);
        assertThat(CdcReadinessStatus.worst(List.of())).isEqualTo(CdcReadinessStatus.OK);
    }

    private void wireConnection(DatasourceEntity e) throws Exception {
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(secretStore.resolve("ref")).thenReturn(new DbCredential("user", "pw"));
        when(registry.forEngine(DbType.POSTGRESQL)).thenReturn(checker);
        HikariDataSource ds = mock(HikariDataSource.class);
        when(ds.getConnection()).thenReturn(mock(Connection.class));
        when(dataSourceFactory.create(any(), any(), anyBoolean())).thenReturn(ds);
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
