package com.bifrost.ops.workspace.persistence.repository;

import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceSettingsRepositoryTest {

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
    WorkspaceSettingsRepository repo;

    @Autowired
    TestEntityManager em;

    @Test
    void storesWorkspaceSettings() {
        UUID wsId = UUID.randomUUID();
        insertTenant(wsId);

        WorkspaceSettingsEntity settings = WorkspaceSettingsEntity.defaults(wsId);
        settings.setSlackEnabled(true);
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T/B/C");
        settings.setEmailRecipientList(List.of("ops@bifrost.io", "dev@bifrost.io"));
        settings.setSeverity("error");
        settings.setLagWarningThreshold(10_000);
        settings.setLagCriticalThreshold(50_000);
        settings.setAiAutonomous(true);
        settings.setAiApprovalWaitMinutes(30);
        settings.setAiProdLock(false);
        repo.saveAndFlush(settings);
        em.clear();

        WorkspaceSettingsEntity out = repo.findById(wsId).orElseThrow();

        assertThat(out.isSlackEnabled()).isTrue();
        assertThat(out.emailRecipientList()).containsExactly("ops@bifrost.io", "dev@bifrost.io");
        assertThat(out.getSeverity()).isEqualTo("error");
        assertThat(out.getLagCriticalThreshold()).isEqualTo(50_000);
        assertThat(out.isAiAutonomous()).isTrue();
        assertThat(out.getAiApprovalWaitMinutes()).isEqualTo(30);
        assertThat(out.isAiProdLock()).isFalse();
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
