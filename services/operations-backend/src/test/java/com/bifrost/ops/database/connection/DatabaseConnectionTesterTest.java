package com.bifrost.ops.database.connection;

import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.global.common.datasource.DbType;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연결 테스트 분류 로직(#26). Spring 컨텍스트 없이 합성 예외로 5종 분류를 검증하고,
 * 닫힌 포트에 실제 연결을 시도해 HikariCP 경로 + 5초 timeout을 확인한다.
 */
class DatabaseConnectionTesterTest {

    private final DatabaseConnectionTester tester = new DatabaseConnectionTester();

    @Test
    void authFailureBySqlState28() {
        assertThat(tester.classify(hikariWrap(new SQLException("password authentication failed", "28P01"))))
                .isEqualTo(DbConnectionFailureReason.AUTH_FAILED);
    }

    @Test
    void authFailureByMariadbVendor1045() {
        assertThat(tester.classify(new SQLException("Access denied for user", "HY000", 1045)))
                .isEqualTo(DbConnectionFailureReason.AUTH_FAILED);
    }

    @Test
    void dbNotFoundByPostgresState3D000() {
        assertThat(tester.classify(hikariWrap(new SQLException("database \"nope\" does not exist", "3D000"))))
                .isEqualTo(DbConnectionFailureReason.DB_NOT_FOUND);
    }

    @Test
    void dbNotFoundByMariadbVendor1049() {
        assertThat(tester.classify(new SQLException("Unknown database 'nope'", "42000", 1049)))
                .isEqualTo(DbConnectionFailureReason.DB_NOT_FOUND);
    }

    @Test
    void connectionRefusedFromCauseChain() {
        Throwable e = new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available",
                new SQLException("conn", new ConnectException("Connection refused")));
        assertThat(tester.classify(e)).isEqualTo(DbConnectionFailureReason.CONNECTION_REFUSED);
    }

    @Test
    void timeoutFromSocketTimeout() {
        Throwable e = new SQLTransientConnectionException("timed out",
                new SocketTimeoutException("connect timed out"));
        assertThat(tester.classify(e)).isEqualTo(DbConnectionFailureReason.TIMEOUT);
    }

    @Test
    void unknownByDefault() {
        assertThat(tester.classify(new RuntimeException("weird")))
                .isEqualTo(DbConnectionFailureReason.UNKNOWN);
    }

    @Test
    void realConnectionToClosedPortIsRefusedWithinTimeout() {
        long start = System.currentTimeMillis();
        ConnectionTestResponse resp =
                tester.test(DbType.POSTGRESQL, "127.0.0.1", 1, "x", "u", "p");
        long tookMs = System.currentTimeMillis() - start;

        assertThat(resp.success()).isFalse();
        assertThat(resp.reason()).isEqualTo(DbConnectionFailureReason.CONNECTION_REFUSED);
        assertThat(tookMs).isLessThan(7000); // 5s timeout + 여유
    }

    /** SQLState/벤더코드를 가진 원인을 HikariCP 래퍼로 감싼다(실제 런타임 형태 모사). */
    private static SQLException hikariWrap(SQLException cause) {
        return new SQLTransientConnectionException("HikariPool-1 - Connection is not available", cause);
    }
}
