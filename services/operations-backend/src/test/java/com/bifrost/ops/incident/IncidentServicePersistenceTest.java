package com.bifrost.ops.incident;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.streaming.SsePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class IncidentServicePersistenceTest {

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
    IncidentRepository incidentRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    TestEntityManager em;

    @Test
    void thresholdAndRecoveryEventsArePersistedWithIncidentId() {
        UUID tenantId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        insertTenant(tenantId);

        IncidentService service = new IncidentService(
                incidentRepository, new EventService(eventRepository), mock(SsePublisher.class),
                mock(IncidentAnalysisTrigger.class));

        service.onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "failed", pipelineId);

        IncidentEntity incident = incidentRepository
                .findByTenantIdAndGroupingKeyAndStatusOrderByOpenedAtAsc(tenantId, groupingKey, "OPEN")
                .getFirst();
        assertThat(eventRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(tenantId, incident.getId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getType()).isEqualTo("PIPELINE_STATUS_CHANGED");
                    assertThat(event.getIncidentId()).isEqualTo(incident.getId());
                    assertThat(event.getCategory()).isEqualTo("PIPELINE");
                });

        assertThat(service.onRecovery(tenantId, groupingKey,
                "PIPELINE_STATUS_CHANGED", "recovered", pipelineId)).isTrue();

        IncidentEntity resolved = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo("RESOLVED");
        assertThat(eventRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(tenantId, incident.getId()))
                .extracting(EventEntity::getType)
                .containsExactly("PIPELINE_STATUS_CHANGED", "PIPELINE_STATUS_CHANGED");
    }

    private void insertTenant(UUID id) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO tenants(id, name, namespace) VALUES (?1, ?2, ?3)")
                .setParameter(1, id)
                .setParameter(2, "ws-" + id.toString().substring(0, 8))
                .setParameter(3, "ns-" + id.toString().substring(0, 8))
                .executeUpdate();
    }
}
