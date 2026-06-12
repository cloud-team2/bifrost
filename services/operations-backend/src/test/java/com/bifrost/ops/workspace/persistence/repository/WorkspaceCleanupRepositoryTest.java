package com.bifrost.ops.workspace.persistence.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(WorkspaceCleanupRepository.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceCleanupRepositoryTest {

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
    WorkspaceCleanupRepository cleanupRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletesWorkspaceOwnedMetadataInFkSafeOrder() {
        UUID wsId = UUID.randomUUID();
        seedWorkspaceMetadata(wsId);

        cleanupRepository.deleteWorkspaceMetadata(wsId);

        assertThat(count("tenants", "id", wsId)).isZero();
        for (String table : tenantScopedTables()) {
            assertThat(count(table, "tenant_id", wsId)).as(table).isZero();
        }
        assertThat(count("workspace_settings", "workspace_id", wsId)).isZero();
        assertThat(count("project_member", "workspace_id", wsId)).isZero();
        assertThat(count("kafka_principal", "workspace_id", wsId)).isZero();
    }

    private void seedWorkspaceMetadata(UUID wsId) {
        UUID incidentId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants(id, name, namespace) VALUES (?, ?, ?)",
                wsId, "ws-" + wsId.toString().substring(0, 8), "ns-" + wsId.toString().substring(0, 8));
        jdbc.update("INSERT INTO workspace_settings(workspace_id) VALUES (?)", wsId);
        jdbc.update("INSERT INTO project_member(workspace_id, user_id, role) VALUES (?, ?, 'OWNER')",
                wsId, UUID.randomUUID());
        jdbc.update("INSERT INTO kafka_principal(id, workspace_id, username) VALUES (?, ?, ?)",
                UUID.randomUUID(), wsId, "user-" + wsId.toString().substring(0, 8));
        jdbc.update("INSERT INTO discovered_services(id, tenant_id, consumer_group_id, subscribed_topics) "
                        + "VALUES (?, ?, ?, ARRAY['topic-a'])",
                UUID.randomUUID(), wsId, "group-" + wsId.toString().substring(0, 8));
        jdbc.update("INSERT INTO incidents(id, tenant_id, grouping_key, severity, title) VALUES (?, ?, ?, 'WARN', ?)",
                incidentId, wsId, "grouping", "incident");
        jdbc.update("INSERT INTO events(id, tenant_id, incident_id, level, type, message) VALUES (?, ?, ?, 'INFO', ?, ?)",
                UUID.randomUUID(), wsId, incidentId, "PIPELINE_CREATED", "event");
        jdbc.update("INSERT INTO approval(id, tenant_id, actor, operation, params_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                approvalId, wsId, "actor", "op", "hash", OffsetDateTime.now().plusMinutes(10));
        jdbc.update("INSERT INTO evidence_ref(id, tenant_id, stage, snapshot) VALUES (?, ?, 'BEFORE', '{}'::jsonb)",
                evidenceId, wsId);
        jdbc.update("INSERT INTO audit_events(id, tenant_id, actor, action, target_type, target_id, approval_id, evidence_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), wsId, "actor", "ACTION", "WORKSPACE", wsId, approvalId, evidenceId);
        jdbc.update("INSERT INTO change_ticket(id, tenant_id, title) VALUES (?, ?, ?)",
                UUID.randomUUID(), wsId, "ticket");
        jdbc.update("INSERT INTO idempotency_key(id, idem_key, tenant_id, expires_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), "idem-" + wsId, wsId, OffsetDateTime.now().plusMinutes(10));
    }

    private long count(String table, String column, UUID id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, id);
        return count == null ? 0L : count;
    }

    private static List<String> tenantScopedTables() {
        return List.of(
                "audit_events",
                "events",
                "incidents",
                "evidence_ref",
                "approval",
                "change_ticket",
                "idempotency_key",
                "discovered_services"
        );
    }
}
