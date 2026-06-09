package com.bifrost.ops.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

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

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String DOCKER_UNAVAILABLE_MESSAGE = "Docker 미가용 — Flyway 마이그레이션 IT skip";
    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String PUBLIC_SCHEMA = "public";
    private static final String CONNECTORS_TABLE = "connectors";
    private static final String USERS_TABLE = "users";
    private static final String TENANTS_TABLE = "tenants";
    private static final String PROJECT_MEMBER_TABLE = "project_member";
    private static final List<String> CONNECTORS_COLUMNS = List.of(
            "pipeline_id",
            "cr_name",
            "state",
            "tasks_max");
    private static final List<String> USERS_COLUMNS = List.of(
            "name",
            "last_login_at");
    private static final List<String> TENANTS_COLUMNS = List.of(
            "timezone",
            "owner_user_id");
    private static final List<String> PROJECT_MEMBER_COLUMNS = List.of(
            "workspace_id",
            "user_id",
            "role",
            "joined_at");
    private static final List<TableColumns> REQUIRED_SCHEMA = List.of(
            new TableColumns(CONNECTORS_TABLE, CONNECTORS_COLUMNS),
            new TableColumns(USERS_TABLE, USERS_COLUMNS),
            new TableColumns(TENANTS_TABLE, TENANTS_COLUMNS),
            new TableColumns(PROJECT_MEMBER_TABLE, PROJECT_MEMBER_COLUMNS));

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @BeforeAll
    static void migratePostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                DOCKER_UNAVAILABLE_MESSAGE);

        POSTGRES.start();

        // 중복 V3가 남아 있으면 여기서 FlywayException이 발생한다.
        flyway().migrate();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void migratesCleanlyAndCreatesExpectedSchema() throws Exception {
        try (Connection conn = connection()) {
            assertTablesWithColumns(conn, PUBLIC_SCHEMA, REQUIRED_SCHEMA);
        }
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(MIGRATION_LOCATION)
                .defaultSchema(PUBLIC_SCHEMA)
                .schemas(PUBLIC_SCHEMA)
                .load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void assertTablesWithColumns(
            Connection conn,
            String schema,
            List<TableColumns> tables
    ) throws Exception {
        for (TableColumns table : tables) {
            assertTableWithColumns(conn, schema, table.table(), table.columns());
        }
    }

    private static void assertTableWithColumns(
            Connection conn,
            String schema,
            String table,
            List<String> columns
    ) throws Exception {
        assertThat(tableExists(conn, schema, table)).as("%s.%s table", schema, table).isTrue();
        for (String column : columns) {
            assertThat(columnExists(conn, schema, table, column))
                    .as("%s.%s.%s column", schema, table, column)
                    .isTrue();
        }
    }

    private static boolean tableExists(Connection conn, String schema, String table) throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, schema, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection conn, String schema, String table, String column) throws Exception {
        try (ResultSet rs = conn.getMetaData().getColumns(null, schema, table, column)) {
            return rs.next();
        }
    }

    private record TableColumns(String table, List<String> columns) {
    }
}
