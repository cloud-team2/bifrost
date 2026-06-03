package com.bifrost.ops.database.persistence.repository;

import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.global.common.datasource.DbType;
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
 * DatasourceRepository 통합 테스트(#27). Flyway(V1~V4)로 실제 스키마를 올린 PostgreSQL에 대해
 * scope 조회와 role 파생용 native 쿼리를 검증한다.
 *
 * <p>Docker가 없으면 (이 개발 샌드박스 등) 전체가 <b>skip</b>된다({@code disabledWithoutDocker}).
 * CI 등 Docker 환경에서 실행된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class DatasourceRepositoryTest {

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
    DatasourceRepository repo;

    @Autowired
    TestEntityManager em;

    private final UUID ws = UUID.randomUUID();

    @Test
    void existsAndScopedLookups() {
        insertTenant(ws);
        DatasourceEntity ds = saveDatasource(ws, "orders");

        assertThat(repo.existsByTenantIdAndName(ws, "orders")).isTrue();
        assertThat(repo.existsByTenantIdAndName(ws, "nope")).isFalse();
        assertThat(repo.findByIdAndTenantId(ds.getId(), ws)).isPresent();
        // 다른 워크스페이스 scope로는 안 보인다
        assertThat(repo.findByIdAndTenantId(ds.getId(), UUID.randomUUID())).isEmpty();
        assertThat(repo.findByTenantIdOrderByCreatedAtDesc(ws)).extracting(DatasourceEntity::getName)
                .containsExactly("orders");
    }

    @Test
    void findSourceDatasourceIdsReflectsPipelineUsage() {
        insertTenant(ws);
        DatasourceEntity used = saveDatasource(ws, "source-db");
        DatasourceEntity unused = saveDatasource(ws, "idle-db");
        insertPipeline(ws, used.getId());

        assertThat(repo.findSourceDatasourceIds(ws))
                .contains(used.getId())
                .doesNotContain(unused.getId());
    }

    @Test
    void findPipelinesUsingDatasourceReturnsRows() {
        insertTenant(ws);
        DatasourceEntity used = saveDatasource(ws, "source-db");
        DatasourceEntity idle = saveDatasource(ws, "idle-db");
        insertPipeline(ws, used.getId());

        assertThat(repo.findPipelinesUsingDatasource(ws, used.getId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getName()).startsWith("pipe-");
                    assertThat(r.getType()).isEqualTo("CDC");      // V2 default
                    assertThat(r.getStatus()).isEqualTo("PENDING"); // V2 default
                });
        assertThat(repo.findPipelinesUsingDatasource(ws, idle.getId())).isEmpty();
    }

    private void insertTenant(UUID id) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO tenants(id, name, namespace) VALUES (?1, ?2, ?3)")
                .setParameter(1, id)
                .setParameter(2, "ws-" + id.toString().substring(0, 8))
                .setParameter(3, "ns-" + id.toString().substring(0, 8))
                .executeUpdate();
    }

    private DatasourceEntity saveDatasource(UUID tenantId, String name) {
        DatasourceEntity e = new DatasourceEntity();
        e.setTenantId(tenantId);
        e.setName(name);
        e.setDbType(DbType.POSTGRESQL);
        e.setHost("h");
        e.setPort(5432);
        e.setDbName("app");
        e.setUsername("user");
        e.setSecretRef("ref-" + name);
        return repo.saveAndFlush(e);
    }

    private void insertPipeline(UUID tenantId, UUID sourceDatasourceId) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO pipelines(id, tenant_id, name, source_datasource_id, tables) "
                                + "VALUES (?1, ?2, ?3, ?4, '[]'::jsonb)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, tenantId)
                .setParameter(3, "pipe-" + sourceDatasourceId.toString().substring(0, 8))
                .setParameter(4, sourceDatasourceId)
                .executeUpdate();
    }
}
