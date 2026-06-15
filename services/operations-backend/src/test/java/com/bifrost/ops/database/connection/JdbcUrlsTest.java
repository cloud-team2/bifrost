package com.bifrost.ops.database.connection;

import com.bifrost.ops.global.common.datasource.DbType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC URL 빌더(#560). 연결 단계 상한을 거는 {@code connectTimeout}이 엔진별 단위로
 * 정확히 붙는지 검증한다 — PostgreSQL은 초, MariaDB는 밀리초.
 */
class JdbcUrlsTest {

    @Test
    void postgresUrlHasConnectTimeoutInSeconds() {
        assertThat(JdbcUrls.build(DbType.POSTGRESQL, "db.internal", 5432, "orders"))
                .isEqualTo("jdbc:postgresql://db.internal:5432/orders?connectTimeout=5");
    }

    @Test
    void mariadbUrlHasConnectTimeoutInMillis() {
        assertThat(JdbcUrls.build(DbType.MARIADB, "db.internal", 3306, "orders"))
                .isEqualTo("jdbc:mariadb://db.internal:3306/orders?connectTimeout=5000");
    }
}
