package com.bifrost.ops.database.service;

import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.database.schema.SchemaIntrospector;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스키마 조회 오케스트레이션(#28). scope 조회 → secret resolve → introspect 위임을 검증한다.
 */
class DatabaseSchemaServiceTest {

    private final DatasourceRepository repo = mock(DatasourceRepository.class);
    private final SecretStore secretStore = mock(SecretStore.class);
    private final SchemaIntrospector introspector = mock(SchemaIntrospector.class);
    private final DatabaseSchemaService service = new DatabaseSchemaService(repo, secretStore, introspector);

    private final UUID ws = UUID.randomUUID();

    @Test
    void resolvesSecretAndDelegatesToIntrospector() {
        DatasourceEntity e = entity();
        when(repo.findByIdAndTenantId(e.getId(), ws)).thenReturn(Optional.of(e));
        when(secretStore.resolve("ref")).thenReturn(new DbCredential("user", "pw"));
        DatabaseSchemaResponse expected = new DatabaseSchemaResponse(List.of());
        when(introspector.introspect(eq(e), eq("pw"))).thenReturn(expected);

        DatabaseSchemaResponse out = service.getSchema(ws, e.getId());

        assertThat(out).isSameAs(expected);
        verify(secretStore).resolve("ref");
        verify(introspector).introspect(e, "pw");
    }

    @Test
    void throwsNotFoundWhenMissing() {
        UUID dbId = UUID.randomUUID();
        when(repo.findByIdAndTenantId(dbId, ws)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSchema(ws, dbId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.DATABASE_NOT_FOUND));
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
