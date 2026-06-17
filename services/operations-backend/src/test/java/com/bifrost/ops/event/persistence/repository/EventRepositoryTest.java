package com.bifrost.ops.event.persistence.repository;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
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
class EventRepositoryTest {

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
    EventRepository repo;

    @Autowired
    TestEntityManager em;

    @Test
    void pipelineWindowQueryExcludesOtherPipelineOldLevelAndTenantRows() {
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID pipeline = UUID.randomUUID();
        UUID otherPipeline = UUID.randomUUID();
        Instant since = Instant.parse("2026-06-17T00:00:00Z");
        UUID match = insertEvent(tenant, pipeline, EventLevel.WARN, "CONSUMER_LAG_WARNING",
                "group=connect-orders-sink lag=9000", null, since.plusSeconds(60));
        insertEvent(tenant, otherPipeline, EventLevel.WARN, "CONSUMER_LAG_WARNING",
                "other pipeline", null, since.plusSeconds(60));
        insertEvent(tenant, pipeline, EventLevel.INFO, "PIPELINE_RECOVERED",
                "info only", null, since.plusSeconds(60));
        insertEvent(otherTenant, pipeline, EventLevel.WARN, "CONSUMER_LAG_WARNING",
                "other tenant", null, since.plusSeconds(60));
        insertEvent(tenant, pipeline, EventLevel.ERROR, "CONNECTOR_TASK_FAILED",
                "old event", null, since.minusSeconds(86_400));

        List<EventEntity> rows = repo.findByTenantIdAndPipelineIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                tenant, pipeline, List.of(EventLevel.WARN, EventLevel.ERROR), since, PageRequest.of(0, 20));

        assertThat(rows).extracting(EventEntity::getId).containsExactly(match);
    }

    @Test
    void connectorScopedQueryKeepsMatchingIncidentOrConnectorTokenOnly() {
        UUID tenant = UUID.randomUUID();
        UUID pipeline = UUID.randomUUID();
        UUID targetIncident = UUID.randomUUID();
        UUID siblingIncident = UUID.randomUUID();
        Instant since = Instant.parse("2026-06-17T00:00:00Z");
        insertTenant(tenant);
        insertIncident(tenant, targetIncident, "connector:orders-sink", "ERROR", "OPEN");
        insertIncident(tenant, siblingIncident, "connector:orders-source", "ERROR", "OPEN");
        UUID byIncident = insertEvent(tenant, pipeline, EventLevel.ERROR, "CONNECTOR_TASK_FAILED",
                "'orders-cdc' sink failed", targetIncident, since.plusSeconds(120));
        UUID byConsumerGroup = insertEvent(tenant, pipeline, EventLevel.WARN, "CONSUMER_LAG_WARNING",
                "consumer lag warning: group=connect-orders-sink lag=9000", null, since.plusSeconds(60));
        insertEvent(tenant, pipeline, EventLevel.ERROR, "CONNECTOR_TASK_FAILED",
                "'orders-cdc' source failed", siblingIncident, since.plusSeconds(180));
        insertEvent(tenant, pipeline, EventLevel.WARN, "CONSUMER_LAG_WARNING",
                "consumer lag warning: group=connect-orders-source lag=9000", null, since.plusSeconds(90));
        insertEvent(tenant, pipeline, EventLevel.ERROR, "CONNECTOR_TASK_FAILED",
                "old target", targetIncident, since.minusSeconds(86_400));

        List<EventEntity> rows = repo.findConnectorScopedEventsOrderByCreatedAtDesc(
                tenant,
                pipeline,
                List.of(EventLevel.WARN, EventLevel.ERROR),
                since,
                List.of(targetIncident),
                "orders-sink",
                "connect-orders-sink",
                PageRequest.of(0, 20));

        assertThat(rows).extracting(EventEntity::getId).containsExactly(byIncident, byConsumerGroup);
    }

    private UUID insertEvent(
            UUID tenantId,
            UUID pipelineId,
            EventLevel level,
            String type,
            String message,
            UUID incidentId,
            Instant createdAt) {
        UUID id = UUID.randomUUID();
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO events(id, tenant_id, pipeline_id, level, type, message, incident_id, created_at)
                        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                        """)
                .setParameter(1, id)
                .setParameter(2, tenantId)
                .setParameter(3, pipelineId)
                .setParameter(4, level.name())
                .setParameter(5, type)
                .setParameter(6, message)
                .setParameter(7, incidentId)
                .setParameter(8, Timestamp.from(createdAt))
                .executeUpdate();
        return id;
    }

    private void insertTenant(UUID id) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO tenants(id, name, namespace) VALUES (?1, ?2, ?3)")
                .setParameter(1, id)
                .setParameter(2, "ws-" + id.toString().substring(0, 8))
                .setParameter(3, "ns-" + id.toString().substring(0, 8))
                .executeUpdate();
    }

    private void insertIncident(UUID tenantId, UUID incidentId, String groupingKey, String severity, String status) {
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO incidents(id, tenant_id, grouping_key, severity, status, title, opened_at, created_at)
                        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7)
                        """)
                .setParameter(1, incidentId)
                .setParameter(2, tenantId)
                .setParameter(3, groupingKey)
                .setParameter(4, severity)
                .setParameter(5, status)
                .setParameter(6, groupingKey)
                .setParameter(7, Timestamp.from(Instant.parse("2026-06-17T00:00:00Z")))
                .executeUpdate();
    }
}
