package com.bifrost.ops.database.service;

import com.bifrost.ops.database.connection.DatabaseConnectionTester;
import com.bifrost.ops.database.connection.DbConnectionFailureReason;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.database.dto.DatabaseMetricsResponse;
import com.bifrost.ops.database.dto.DatabasePipelineSummary;
import com.bifrost.ops.database.dto.DatabaseRegisterRequest;
import com.bifrost.ops.database.dto.DatabaseResponse;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.database.persistence.repository.PipelineSummaryRow;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretContext;
import com.bifrost.ops.secret.SecretStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database 등록·목록·상세 로직(#27). Spring 컨텍스트 없이 협력자를 목으로 검증한다.
 */
class DatabaseServiceTest {

    private final DatasourceRepository repo = mock(DatasourceRepository.class);
    private final SecretStore secretStore = mock(SecretStore.class);
    private final DatabaseConnectionTester tester = mock(DatabaseConnectionTester.class);
    private final DatabaseService service = new DatabaseService(repo, secretStore, tester);

    private final UUID ws = UUID.randomUUID();

    private DatabaseRegisterRequest req(String name) {
        return new DatabaseRegisterRequest(name, "postgresql", "h", 5432, "app", "user", "pw");
    }

    @Test
    void registerStoresSecretAndReturnsMaskedResponse() {
        when(repo.existsByTenantIdAndName(ws, "orders")).thenReturn(false);
        when(tester.test(eq(DbType.POSTGRESQL), any(), anyInt(), any(), any(), any()))
                .thenReturn(ConnectionTestResponse.ok(10));
        when(secretStore.put(any(SecretContext.class), any(DbCredential.class))).thenReturn("bifrost-ds-ref");
        when(repo.save(any(DatasourceEntity.class))).thenAnswer(inv -> {
            DatasourceEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());   // 실DB의 @PrePersist 대체
            return e;
        });

        DatabaseResponse resp = service.register(ws, req("orders"));

        // 자격증명 미노출
        assertThat(resp.password()).isEqualTo("****");
        assertThat(resp.name()).isEqualTo("orders");
        assertThat(resp.engine()).isEqualTo("postgresql");
        assertThat(resp.roles()).isEmpty();

        // SecretStore에 user/password 보관, 메타DB엔 secret_ref만
        ArgumentCaptor<DbCredential> cred = ArgumentCaptor.forClass(DbCredential.class);
        verify(secretStore).put(any(SecretContext.class), cred.capture());
        assertThat(cred.getValue().user()).isEqualTo("user");
        assertThat(cred.getValue().password()).isEqualTo("pw");

        ArgumentCaptor<DatasourceEntity> saved = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getSecretRef()).isEqualTo("bifrost-ds-ref");
        assertThat(saved.getValue().getTenantId()).isEqualTo(ws);
    }

    @Test
    void registerRejectsDuplicateName() {
        when(repo.existsByTenantIdAndName(ws, "orders")).thenReturn(true);
        assertThatThrownBy(() -> service.register(ws, req("orders")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.DATABASE_NAME_CONFLICT));
        verify(secretStore, never()).put(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void registerFailsAndDoesNotStoreWhenConnectionFails() {
        when(repo.existsByTenantIdAndName(ws, "orders")).thenReturn(false);
        when(tester.test(any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(ConnectionTestResponse.fail(DbConnectionFailureReason.AUTH_FAILED, 8));

        assertThatThrownBy(() -> service.register(ws, req("orders")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.DATABASE_CONNECTION_FAILED));
        verify(secretStore, never()).put(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void listDerivesSourceRoleAndAppliesFilters() {
        DatasourceEntity pg = entity("orders", DbType.POSTGRESQL);
        DatasourceEntity maria = entity("legacy", DbType.MARIADB);
        when(repo.findByTenantIdOrderByCreatedAtDesc(ws)).thenReturn(List.of(pg, maria));
        when(repo.findSourceDatasourceIds(ws)).thenReturn(List.of(pg.getId())); // pg만 source

        // 필터 없음 → 둘 다, pg는 roles=[source]
        List<DatabaseResponse> all = service.list(ws, null, null, null);
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(r -> r.name().equals("orders")).findFirst().orElseThrow().roles())
                .containsExactly("source");

        // role=source → pg만
        assertThat(service.list(ws, "source", null, null)).extracting(DatabaseResponse::name)
                .containsExactly("orders");
        // engine=mariadb → maria만
        assertThat(service.list(ws, null, "mariadb", null)).extracting(DatabaseResponse::name)
                .containsExactly("legacy");
        // q=ord → orders만
        assertThat(service.list(ws, null, null, "ORD")).extracting(DatabaseResponse::name)
                .containsExactly("orders");
        // role=sink → 현재 미모델링이라 비어있음
        assertThat(service.list(ws, "sink", null, null)).isEmpty();
    }

    @Test
    void getReturnsMaskedOrThrows() {
        DatasourceEntity e = entity("orders", DbType.POSTGRESQL);
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(repo.findSourceDatasourceIds(ws)).thenReturn(List.of());
        assertThat(service.get(ws, e.getId()).password()).isEqualTo("****");

        UUID missing = UUID.randomUUID();
        when(repo.findByIdAndTenantId(missing, ws)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(ws, missing))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.DATABASE_NOT_FOUND));
    }

    @Test
    void metricsReturnsStubAndChecksExistence() {
        DatasourceEntity e = entity("orders", DbType.POSTGRESQL);
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        DatabaseMetricsResponse m = service.getMetrics(ws, e.getId());
        assertThat(m.stub()).isTrue();

        UUID missing = UUID.randomUUID();
        when(repo.findByIdAndTenantId(missing, ws)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMetrics(ws, missing))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.DATABASE_NOT_FOUND));
    }

    @Test
    void listPipelinesMapsRows() {
        DatasourceEntity e = entity("orders", DbType.POSTGRESQL);
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        PipelineSummaryRow row = mock(PipelineSummaryRow.class);
        UUID pid = UUID.randomUUID();
        when(row.getId()).thenReturn(pid);
        when(row.getName()).thenReturn("orders-cdc");
        when(row.getType()).thenReturn("CDC");
        when(row.getStatus()).thenReturn("ACTIVE");
        when(repo.findPipelinesUsingDatasource(ws, e.getId())).thenReturn(List.of(row));

        List<DatabasePipelineSummary> out = service.listPipelines(ws, e.getId());

        assertThat(out).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(pid.toString());
            assertThat(p.name()).isEqualTo("orders-cdc");
            assertThat(p.type()).isEqualTo("CDC");
            assertThat(p.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void deleteAllForWorkspaceRemovesDatasourcesAndSecretsWhenUnused() {
        DatasourceEntity one = entity("orders", DbType.POSTGRESQL);
        DatasourceEntity two = entity("warehouse", DbType.MARIADB);
        one.setSecretRef("ref-orders");
        two.setSecretRef("ref-warehouse");
        when(repo.findSourceDatasourceIds(ws)).thenReturn(List.of());
        when(repo.findSinkDatasourceIds(ws)).thenReturn(List.of());
        when(repo.findByTenantIdOrderByCreatedAtDesc(ws)).thenReturn(List.of(one, two));

        service.deleteAllForWorkspace(ws);

        verify(secretStore).delete(one.getSecretRef());
        verify(secretStore).delete(two.getSecretRef());
        verify(repo).delete(one);
        verify(repo).delete(two);
        verify(repo).flush();
    }

    @Test
    void deleteAllForWorkspaceRejectsDatasourcesUsedByPipelines() {
        UUID dbId = UUID.randomUUID();
        when(repo.findSourceDatasourceIds(ws)).thenReturn(List.of(dbId));

        assertThatThrownBy(() -> service.deleteAllForWorkspace(ws))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(secretStore, never()).delete(any());
        verify(repo, never()).delete(any());
    }

    private DatasourceEntity entity(String name, DbType type) {
        DatasourceEntity e = new DatasourceEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(ws);
        e.setName(name);
        e.setDbType(type);
        e.setHost("h");
        e.setPort(5432);
        e.setDbName("app");
        e.setUsername("user");
        e.setSecretRef("ref");
        return e;
    }
}
