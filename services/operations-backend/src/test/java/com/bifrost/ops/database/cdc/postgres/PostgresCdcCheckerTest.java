package com.bifrost.ops.database.cdc.postgres;

import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresCdcChecker 통합 테스트(#29). 기본 PostgreSQL(wal_level=replica)에 대해 BLOCKED 판정과
 * hint를 검증한다. Docker 없으면 skip.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresCdcCheckerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void defaultPostgresIsBlockedByWalLevel() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            List<CdcCheck> checks = new PostgresCdcChecker().check(conn);

            CdcCheck walLevel = byName(checks, "WAL Level");
            assertThat(walLevel.status()).isEqualTo(CdcReadinessStatus.BLOCKED); // 기본 replica
            assertThat(walLevel.actual()).isEqualTo("replica");
            assertThat(walLevel.expected()).isEqualTo("logical");
            assertThat(walLevel.hint()).isNotNull();

            // 기본 컨테이너는 max_wal_senders/slots > 0
            assertThat(byName(checks, "Max WAL Senders").status()).isEqualTo(CdcReadinessStatus.OK);
            assertThat(CdcReadinessStatus.worst(checks.stream().map(CdcCheck::status).toList()))
                    .isEqualTo(CdcReadinessStatus.BLOCKED);
        }
    }

    private static CdcCheck byName(List<CdcCheck> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
