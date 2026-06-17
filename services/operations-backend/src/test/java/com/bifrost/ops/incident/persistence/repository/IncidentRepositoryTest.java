package com.bifrost.ops.incident.persistence.repository;

import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class IncidentRepositoryTest {

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
    IncidentRepository repo;

    @Autowired
    TestEntityManager em;

    @Test
    void scopedAlertsRequirePipelineEventOwnershipForDatabaseIncidentsBeforePagination() {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID otherPipelineId = UUID.randomUUID();
        UUID datasourceId = UUID.randomUUID();
        UUID connectorIncidentId = UUID.randomUUID();
        UUID ownedDatabaseIncidentId = UUID.randomUUID();
        UUID foreignDatabaseIncidentId = UUID.randomUUID();
        UUID databaseWithoutEventId = UUID.randomUUID();
        UUID otherConnectorIncidentId = UUID.randomUUID();

        insertTenant(tenantId);
        insertIncident(tenantId, foreignDatabaseIncidentId, IncidentGroupingKeys.datasource(datasourceId),
                "DATABASE", datasourceId, "foreign DB", Instant.parse("2026-06-17T00:05:00Z"));
        insertIncident(tenantId, connectorIncidentId, IncidentGroupingKeys.connectorWorker("orders-sink"),
                "CONNECTOR", UUID.randomUUID(), "orders sink failed", Instant.parse("2026-06-17T00:04:00Z"));
        insertIncident(tenantId, ownedDatabaseIncidentId, IncidentGroupingKeys.datasource(datasourceId),
                "DATABASE", datasourceId, "orders sink DB failed", Instant.parse("2026-06-17T00:03:00Z"));
        insertIncident(tenantId, databaseWithoutEventId, IncidentGroupingKeys.datasource(datasourceId),
                "DATABASE", datasourceId, "unowned DB", Instant.parse("2026-06-17T00:02:00Z"));
        insertIncident(tenantId, otherConnectorIncidentId, IncidentGroupingKeys.connectorWorker("orders-source"),
                "CONNECTOR", UUID.randomUUID(), "orders source failed", Instant.parse("2026-06-17T00:01:00Z"));
        insertEvent(tenantId, otherPipelineId, foreignDatabaseIncidentId);
        insertEvent(tenantId, pipelineId, ownedDatabaseIncidentId);

        List<IncidentEntity> rows = repo.findScopedAlertsByTenantIdOrderByOpenedAtDesc(
                tenantId,
                null,
                null,
                List.of(IncidentGroupingKeys.connectorWorker("orders-sink"), IncidentGroupingKeys.datasource(datasourceId)),
                List.of(datasourceId),
                pipelineId,
                PageRequest.of(0, 2));

        assertThat(rows).extracting(IncidentEntity::getId)
                .containsExactly(connectorIncidentId, ownedDatabaseIncidentId);
    }

    private void insertTenant(UUID id) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO tenants(id, name, namespace) VALUES (?1, ?2, ?3)")
                .setParameter(1, id)
                .setParameter(2, "ws-" + id.toString().substring(0, 8))
                .setParameter(3, "ns-" + id.toString().substring(0, 8))
                .executeUpdate();
    }

    private void insertIncident(
            UUID tenantId,
            UUID incidentId,
            String groupingKey,
            String sourceType,
            UUID sourceId,
            String title,
            Instant openedAt) {
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO incidents(
                            id, tenant_id, grouping_key, severity, status, title,
                            source_type, source_id, opened_at, created_at
                        )
                        VALUES (?1, ?2, ?3, 'CRITICAL', 'OPEN', ?4, ?5, ?6, ?7, ?7)
                        """)
                .setParameter(1, incidentId)
                .setParameter(2, tenantId)
                .setParameter(3, groupingKey)
                .setParameter(4, title)
                .setParameter(5, sourceType)
                .setParameter(6, sourceId)
                .setParameter(7, Timestamp.from(openedAt))
                .executeUpdate();
    }

    private void insertEvent(UUID tenantId, UUID pipelineId, UUID incidentId) {
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO events(id, tenant_id, pipeline_id, level, type, message, incident_id, created_at)
                        VALUES (?1, ?2, ?3, 'ERROR', 'PIPELINE_STATUS_CHANGED', 'pipeline failed', ?4, ?5)
                        """)
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, tenantId)
                .setParameter(3, pipelineId)
                .setParameter(4, incidentId)
                .setParameter(5, Timestamp.from(Instant.parse("2026-06-17T00:06:00Z")))
                .executeUpdate();
    }
}
