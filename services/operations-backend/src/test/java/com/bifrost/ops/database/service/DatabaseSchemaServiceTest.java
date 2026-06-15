package com.bifrost.ops.database.service;

import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.inspector.DatabaseInspectorFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseSchemaServiceTest {

    private final DatasourceRepository repo = mock(DatasourceRepository.class);
    private final SecretStore secretStore = mock(SecretStore.class);
    private final DatabaseInspectorFactory inspectorFactory = mock(DatabaseInspectorFactory.class);
    private final DatabaseInspector inspector = mock(DatabaseInspector.class);
    private final DatabaseSchemaService service =
            new DatabaseSchemaService(repo, secretStore, inspectorFactory);

    private final UUID ws = UUID.randomUUID();

    @Test
    void resolvesSecretAndDelegatesToInspector() throws Exception {
        DatasourceEntity e = entity();
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(secretStore.resolve("ref")).thenReturn(new DbCredential("user", "pw"));
        when(inspectorFactory.create(any(), any())).thenReturn(inspector);

        DatabaseInspector.TableInfo tableInfo = new DatabaseInspector.TableInfo(
                "public", "orders", 128L, 4096L, true,
                List.of(new DatabaseInspector.ColumnInfo("id", "int4", false, true, true)),
                List.of("id"));
        when(inspector.listTables()).thenReturn(List.of(tableInfo));

        DatabaseSchemaResponse out = service.getSchema(ws, e.getId());

        assertThat(out.tables()).hasSize(1);
        assertThat(out.tables().get(0).name()).isEqualTo("orders");
        assertThat(out.tables().get(0).approximateRowCount()).isEqualTo(128L);
        assertThat(out.tables().get(0).totalSizeBytes()).isEqualTo(4096L);
        assertThat(out.tables().get(0).columns()).hasSize(1);
        assertThat(out.tables().get(0).columns().get(0).primaryKey()).isTrue();

        verify(secretStore).resolve("ref");
        verify(inspector).listTables();
    }

    @Test
    void throwsNotFoundWhenMissing() {
        UUID dbId = UUID.randomUUID();
        when(repo.findByIdAndTenantId(dbId, ws)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSchema(ws, dbId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.DATABASE_NOT_FOUND));
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
