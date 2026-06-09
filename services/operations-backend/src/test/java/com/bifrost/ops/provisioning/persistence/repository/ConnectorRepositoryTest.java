package com.bifrost.ops.provisioning.persistence.repository;

import com.bifrost.ops.pipeline.ConnectorRuntimeState;
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

/**
 * ConnectorRepository 집계 native query 검증.
 *
 * <p>Docker가 없으면 skip된다({@code disabledWithoutDocker}). CI 등 Docker 환경에서 실제
 * PostgreSQL/Flyway schema로 tenant-scope connector 집계를 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ConnectorRepositoryTest {

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
    ConnectorRepository repo;

    @Autowired
    TestEntityManager em;

    @Test
    void countsConnectorsByTenantAndFailedStates() {
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID pipeline = createPipeline(tenant);
        UUID otherPipeline = createPipeline(otherTenant);

        insertConnector(pipeline, "orders-source", ConnectorRuntimeState.RUNNING);
        insertConnector(pipeline, "orders-sink", ConnectorRuntimeState.FAILED);
        insertConnector(pipeline, "orders-audit", ConnectorRuntimeState.PARTIALLY_FAILED);
        insertConnector(otherPipeline, "other-sink", ConnectorRuntimeState.FAILED);

        assertThat(repo.countByTenantId(tenant)).isEqualTo(3L);
        assertThat(repo.countByTenantIdAndStateIn(
                tenant,
                ConnectorRuntimeState.FAILED.name(),
                ConnectorRuntimeState.PARTIALLY_FAILED.name()))
                .isEqualTo(2L);
    }

    private UUID createPipeline(UUID tenantId) {
        insertTenant(tenantId);
        UUID datasourceId = UUID.randomUUID();
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO datasources(id, tenant_id, name, db_type, host, port, db_name, username, secret_ref) "
                                + "VALUES (?1, ?2, ?3, 'POSTGRESQL', 'h', 5432, 'app', 'user', ?4)")
                .setParameter(1, datasourceId)
                .setParameter(2, tenantId)
                .setParameter(3, "ds-" + datasourceId.toString().substring(0, 8))
                .setParameter(4, "ref-" + datasourceId)
                .executeUpdate();

        UUID pipelineId = UUID.randomUUID();
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO pipelines(id, tenant_id, name, source_datasource_id, tables) "
                                + "VALUES (?1, ?2, ?3, ?4, '[]'::jsonb)")
                .setParameter(1, pipelineId)
                .setParameter(2, tenantId)
                .setParameter(3, "pipe-" + pipelineId.toString().substring(0, 8))
                .setParameter(4, datasourceId)
                .executeUpdate();
        return pipelineId;
    }

    private void insertTenant(UUID id) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO tenants(id, name, namespace) VALUES (?1, ?2, ?3)")
                .setParameter(1, id)
                .setParameter(2, "ws-" + id.toString().substring(0, 8))
                .setParameter(3, "ns-" + id.toString().substring(0, 8))
                .executeUpdate();
    }

    private void insertConnector(UUID pipelineId, String name, ConnectorRuntimeState state) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO connectors(id, pipeline_id, cr_name, kind, connector_class, state, tasks_max) "
                                + "VALUES (?1, ?2, ?3, 'SOURCE', 'io.debezium.connector.postgresql.PostgresConnector', ?4, 1)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, pipelineId)
                .setParameter(3, name)
                .setParameter(4, state.name())
                .executeUpdate();
    }
}
