package com.bifrost.ops.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Flyway 마이그레이션 정합 검증(#43).
 *
 * <p>핵심 목적: V3 버전 충돌(연속된 두 V3 파일)이 해소됐는지, connectors 테이블이 정상 생성되는지
 * 실제 PostgreSQL에 적용해 확인한다. 중복 버전이 남아 있으면 Flyway가 즉시 실패한다.
 *
 * <p>Docker가 없으면 자동 skip된다(로컬), CI(Docker)에서 실제 검증된다.
 */
class FlywayMigrationIT {

    @Test
    void migratesCleanlyAndCreatesConnectorsTable() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker 미가용 — Flyway 마이그레이션 IT skip");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();

            Flyway flyway = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load();

            // 중복 V3가 남아 있으면 여기서 FlywayException이 발생한다.
            flyway.migrate();

            try (Connection conn = DriverManager.getConnection(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                assertThat(tableExists(conn, "connectors")).isTrue();
                assertThat(columnExists(conn, "connectors", "pipeline_id")).isTrue();
                assertThat(columnExists(conn, "connectors", "cr_name")).isTrue();
                assertThat(columnExists(conn, "connectors", "state")).isTrue();
                assertThat(columnExists(conn, "connectors", "tasks_max")).isTrue();
            }
        }
    }

    private boolean tableExists(Connection conn, String table) throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
