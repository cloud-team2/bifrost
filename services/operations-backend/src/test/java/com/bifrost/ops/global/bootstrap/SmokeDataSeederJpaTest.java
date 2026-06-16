package com.bifrost.ops.global.bootstrap;

import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SmokeDataSeederJpaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    WorkspaceRepository workspaceRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ProjectMemberRepository memberRepository;
    @Autowired
    DatasourceRepository datasourceRepository;
    @Autowired
    PipelineRepository pipelineRepository;
    @Autowired
    ConnectorRepository connectorRepository;
    @Autowired
    IncidentRepository incidentRepository;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void runTwicePersistsOneFixedSmokeGraphWithFlywaySchema() {
        SmokeDataSeeder seeder = seeder(true);

        seeder.run();
        seeder.run();

        assertThat(countById("tenants", SmokeDataSeeder.WORKSPACE_ID)).isEqualTo(1L);
        assertThat(countById("users", SmokeDataSeeder.USER_ID)).isEqualTo(1L);
        assertThat(countWhere("project_member", "workspace_id", SmokeDataSeeder.WORKSPACE_ID)).isEqualTo(1L);
        assertThat(countById("datasources", SmokeDataSeeder.DATASOURCE_ID)).isEqualTo(1L);
        assertThat(countById("pipelines", SmokeDataSeeder.PIPELINE_ID)).isEqualTo(1L);
        assertThat(countById("connectors", SmokeDataSeeder.CONNECTOR_ID)).isEqualTo(1L);
        assertThat(countById("incidents", SmokeDataSeeder.INCIDENT_ID)).isEqualTo(1L);
        assertThat(countById("events", SmokeDataSeeder.EVENT_ID)).isEqualTo(1L);

        assertThat(stringValue("SELECT namespace FROM tenants WHERE id = ?", SmokeDataSeeder.WORKSPACE_ID))
                .isEqualTo(SmokeDataSeeder.WORKSPACE_NAMESPACE);
        assertThat(stringValue("SELECT source_connector_name FROM pipelines WHERE id = ?",
                SmokeDataSeeder.PIPELINE_ID))
                .isEqualTo(ConnectorNaming.sourceConnectorName(SmokeDataSeeder.PIPELINE_ID));
        assertThat(stringValue("SELECT cr_name FROM connectors WHERE id = ?", SmokeDataSeeder.CONNECTOR_ID))
                .isEqualTo(ConnectorNaming.sourceConnectorName(SmokeDataSeeder.PIPELINE_ID));
        assertThat(stringValue("SELECT incident_id::text FROM events WHERE id = ?", SmokeDataSeeder.EVENT_ID))
                .isEqualTo(SmokeDataSeeder.INCIDENT_ID.toString());
    }

    @Test
    void fixedWorkspaceIdMismatchFailsClosedWithoutAddingSeedChildren() {
        jdbc.update("""
                        INSERT INTO tenants(id, name, namespace, status, owner_user_id)
                        VALUES (?, 'Real Project', 'real-project', 'ACTIVE', ?)
                        """,
                SmokeDataSeeder.WORKSPACE_ID,
                SmokeDataSeeder.USER_ID);

        seeder(true).run();

        assertThat(countById("tenants", SmokeDataSeeder.WORKSPACE_ID)).isEqualTo(1L);
        assertThat(countById("users", SmokeDataSeeder.USER_ID)).isZero();
        assertThat(countWhere("project_member", "workspace_id", SmokeDataSeeder.WORKSPACE_ID)).isZero();
        assertThat(countWhere("datasources", "tenant_id", SmokeDataSeeder.WORKSPACE_ID)).isZero();
        assertThat(countWhere("pipelines", "tenant_id", SmokeDataSeeder.WORKSPACE_ID)).isZero();
        assertThat(countWhere("incidents", "tenant_id", SmokeDataSeeder.WORKSPACE_ID)).isZero();
        assertThat(countWhere("events", "tenant_id", SmokeDataSeeder.WORKSPACE_ID)).isZero();
    }

    private SmokeDataSeeder seeder(boolean enabled) {
        return new SmokeDataSeeder(
                workspaceRepository,
                userRepository,
                memberRepository,
                datasourceRepository,
                pipelineRepository,
                connectorRepository,
                incidentRepository,
                eventRepository,
                passwordEncoder(),
                enabled,
                ""
        );
    }

    private PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "encoded-" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encodedPassword.equals(encode(rawPassword));
            }
        };
    }

    private long countById(String table, UUID id) {
        return countWhere(table, "id", id);
    }

    private long countWhere(String table, String column, UUID id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, id);
        return count == null ? 0L : count;
    }

    private String stringValue(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }
}
