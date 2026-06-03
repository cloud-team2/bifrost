package com.bifrost.ops.database.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.connection.DbConnectionFailureReason;
import com.bifrost.ops.database.dto.ConnectionTestRequest;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.dto.DatabaseMetricsResponse;
import com.bifrost.ops.database.dto.DatabasePipelineSummary;
import com.bifrost.ops.database.dto.DatabaseRegisterRequest;
import com.bifrost.ops.database.dto.DatabaseResponse;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.service.CdcReadinessService;
import com.bifrost.ops.database.service.DatabaseSchemaService;
import com.bifrost.ops.database.service.DatabaseService;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Database 컨트롤러(#26 연결 테스트 + #27 등록·목록·상세). Spring 컨텍스트 없이 직접 호출해
 * scope 검증·engine 파싱·위임만 본다.
 */
class DatabaseControllerTest {

    private final DatabaseService service = mock(DatabaseService.class);
    private final DatabaseSchemaService schemaService = mock(DatabaseSchemaService.class);
    private final CdcReadinessService cdcReadinessService = mock(CdcReadinessService.class);
    private final DatabaseController controller =
            new DatabaseController(service, schemaService, cdcReadinessService);

    private final UUID wsId = UUID.randomUUID();
    private final AuthenticatedUser member = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

    // ---------- #26 연결 테스트 ----------

    @Test
    void connectionTestDelegates() {
        when(service.testConnection(eq(DbType.POSTGRESQL), eq("h"), eq(5432), eq("db"), eq("u"), eq("p")))
                .thenReturn(ConnectionTestResponse.ok(12));
        ConnectionTestRequest req = new ConnectionTestRequest("postgresql", "h", 5432, "db", "u", "p");

        ConnectionTestResponse resp = controller.connectionTest(wsId, member, req);

        assertThat(resp.success()).isTrue();
    }

    @Test
    void connectionTestAcceptsEngineCaseInsensitively() {
        when(service.testConnection(eq(DbType.MARIADB), any(), anyInt(), any(), any(), any()))
                .thenReturn(ConnectionTestResponse.fail(DbConnectionFailureReason.AUTH_FAILED, 5));
        ConnectionTestRequest req = new ConnectionTestRequest("MariaDB", "h", 3306, "db", "u", "p");

        assertThat(controller.connectionTest(wsId, member, req).reason())
                .isEqualTo(DbConnectionFailureReason.AUTH_FAILED);
    }

    @Test
    void connectionTestRejectsUnknownEngine() {
        ConnectionTestRequest req = new ConnectionTestRequest("oracle", "h", 1521, "db", "u", "p");
        assertThatThrownBy(() -> controller.connectionTest(wsId, member, req))
                .isInstanceOf(ApiException.class);
    }

    // ---------- scope ----------

    @Test
    void rejectsAccessToOtherWorkspace() {
        AuthenticatedUser other = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "x@bifrost.io");
        assertThatThrownBy(() -> controller.get(wsId, UUID.randomUUID(), other))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_OWNED_BY_PROJECT));
    }

    @Test
    void rejectsUnauthenticated() {
        assertThatThrownBy(() -> controller.list(wsId, null, null, null, null))
                .isInstanceOf(ApiException.class);
    }

    // ---------- #27 등록·목록·상세 ----------

    @Test
    void registerReturns201AndDelegates() {
        DatabaseResponse body = sample("orders-db");
        when(service.register(eq(wsId), any(DatabaseRegisterRequest.class))).thenReturn(body);
        DatabaseRegisterRequest req =
                new DatabaseRegisterRequest("orders-db", "postgresql", "h", 5432, "app", "u", "p");

        ResponseEntity<DatabaseResponse> resp = controller.register(wsId, member, req);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().password()).isEqualTo("****");
    }

    @Test
    void listDelegatesWithFilters() {
        when(service.list(wsId, "source", "postgresql", "ord")).thenReturn(List.of(sample("orders-db")));
        List<DatabaseResponse> out = controller.list(wsId, member, "source", "postgresql", "ord");
        assertThat(out).hasSize(1);
    }

    @Test
    void getDelegates() {
        DatabaseResponse body = sample("orders-db");
        UUID dbId = UUID.fromString(body.id());
        when(service.get(wsId, dbId)).thenReturn(body);
        assertThat(controller.get(wsId, dbId, member).name()).isEqualTo("orders-db");
    }

    @Test
    void schemaDelegates() {
        UUID dbId = UUID.randomUUID();
        DatabaseSchemaResponse body = new DatabaseSchemaResponse(List.of(
                new DatabaseSchemaResponse.TableSchema("public", "orders", List.of(
                        new DatabaseSchemaResponse.ColumnSchema("id", "int8", false, true, true)))));
        when(schemaService.getSchema(wsId, dbId)).thenReturn(body);

        DatabaseSchemaResponse out = controller.schema(wsId, dbId, member);

        assertThat(out.tables()).hasSize(1);
        assertThat(out.tables().get(0).columns().get(0).primaryKey()).isTrue();
    }

    @Test
    void metricsDelegates() {
        UUID dbId = UUID.randomUUID();
        when(service.getMetrics(wsId, dbId)).thenReturn(DatabaseMetricsResponse.placeholder());
        assertThat(controller.metrics(wsId, dbId, member).stub()).isTrue();
    }

    @Test
    void pipelinesDelegates() {
        UUID dbId = UUID.randomUUID();
        when(service.listPipelines(wsId, dbId)).thenReturn(List.of(
                new DatabasePipelineSummary(UUID.randomUUID().toString(), "orders-cdc", "CDC", "ACTIVE")));
        assertThat(controller.pipelines(wsId, dbId, member)).hasSize(1);
    }

    @Test
    void cdcReadinessDelegates() {
        UUID dbId = UUID.randomUUID();
        CdcReadinessResponse body = new CdcReadinessResponse(CdcReadinessStatus.BLOCKED, List.of(
                CdcReadinessResponse.CdcCheck.of("WAL Level", CdcReadinessStatus.BLOCKED,
                        "replica", "logical", "ALTER SYSTEM SET wal_level = logical;")));
        when(cdcReadinessService.check(wsId, dbId)).thenReturn(body);

        CdcReadinessResponse out = controller.cdcReadiness(wsId, dbId, member);

        assertThat(out.overallStatus()).isEqualTo(CdcReadinessStatus.BLOCKED);
        assertThat(out.checks().get(0).hint()).isNotNull();
    }

    private static DatabaseResponse sample(String name) {
        return new DatabaseResponse(UUID.randomUUID().toString(), name, "postgresql",
                "h", 5432, "app", "u", "****", null, List.of(), Instant.now());
    }
}
