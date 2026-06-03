package com.bifrost.ops.database.controller;

import com.bifrost.ops.database.connection.DatabaseConnectionTester;
import com.bifrost.ops.database.connection.DbConnectionFailureReason;
import com.bifrost.ops.database.dto.ConnectionTestRequest;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연결 테스트 컨트롤러(#26). Spring 컨텍스트 없이 직접 호출해 engine 파싱·위임만 검증한다.
 */
class DatabaseControllerTest {

    private final DatabaseConnectionTester tester = mock(DatabaseConnectionTester.class);
    private final DatabaseController controller = new DatabaseController(tester);

    @Test
    void delegatesAndReturnsResult() {
        when(tester.test(eq(DbType.POSTGRESQL), eq("h"), eq(5432), eq("db"), eq("u"), eq("p")))
                .thenReturn(ConnectionTestResponse.ok(12));
        ConnectionTestRequest req = new ConnectionTestRequest("postgresql", "h", 5432, "db", "u", "p");

        ConnectionTestResponse resp = controller.connectionTest(UUID.randomUUID(), req);

        assertThat(resp.success()).isTrue();
        assertThat(resp.latencyMs()).isEqualTo(12);
    }

    @Test
    void acceptsEngineCaseInsensitively() {
        when(tester.test(eq(DbType.MARIADB), any(), anyInt(), any(), any(), any()))
                .thenReturn(ConnectionTestResponse.fail(DbConnectionFailureReason.AUTH_FAILED, 5));
        ConnectionTestRequest req = new ConnectionTestRequest("MariaDB", "h", 3306, "db", "u", "p");

        ConnectionTestResponse resp = controller.connectionTest(UUID.randomUUID(), req);

        assertThat(resp.success()).isFalse();
        assertThat(resp.reason()).isEqualTo(DbConnectionFailureReason.AUTH_FAILED);
    }

    @Test
    void rejectsUnknownEngine() {
        ConnectionTestRequest req = new ConnectionTestRequest("oracle", "h", 1521, "db", "u", "p");
        assertThatThrownBy(() -> controller.connectionTest(UUID.randomUUID(), req))
                .isInstanceOf(ApiException.class);
    }
}
