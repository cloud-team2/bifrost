package com.bifrost.ops.database.cdc.mariadb;

import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MariadbCdcChecker 통합 테스트(#29). 실제 MariaDB에 대해 5개 점검 항목이 예외 없이 산출되는지
 * 검증한다(엔진별 SHOW VARIABLES/GRANTS 실행 확인). Docker 없으면 skip.
 */
@Testcontainers(disabledWithoutDocker = true)
class MariadbCdcCheckerTest {

    @Container
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4");

    @Test
    void producesFiveChecksWithoutError() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())) {
            List<CdcCheck> checks = new MariadbCdcChecker().check(conn);

            assertThat(checks).extracting(CdcCheck::name).containsExactly(
                    "Binary Log", "Binlog Format", "Binlog Row Image", "Server ID", "REPLICATION 권한");
            // 각 항목은 actual을 채운다(질의 성공)
            assertThat(checks).allSatisfy(c -> assertThat(c.status()).isNotNull());
        }
    }
}
