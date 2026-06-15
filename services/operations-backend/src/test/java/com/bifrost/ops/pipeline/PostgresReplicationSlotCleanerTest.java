package com.bifrost.ops.pipeline;

import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

import static com.bifrost.ops.pipeline.PostgresReplicationSlotCleaner.slotName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresReplicationSlotCleanerTest {

    @Mock private DatasourceRepository datasourceRepository;
    @Mock private SecretStore secretStore;
    @Mock private DynamicDataSourceFactory dataSourceFactory;
    @Mock private HikariDataSource hikariDs;
    @Mock private Connection connection;
    @Mock private PreparedStatement preparedStatement;

    @InjectMocks
    private PostgresReplicationSlotCleaner cleaner;

    // ---------- slotName ----------

    @Test
    void slotNameFollowsDebeziumConvention() {
        UUID pid = UUID.fromString("6575fbaa-4d5d-43dd-84e6-dcf15b5e7adc");
        assertThat(slotName("demo-team", pid)).isEqualTo("bif_demo_team_6575fbaa");
    }

    @Test
    void slotNameNormalizesSpecialChars() {
        UUID pid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String name = slotName("my.project/key", pid);
        assertThat(name).matches("[a-z0-9_]+");
    }

    @Test
    void slotNameTruncatesAt63() {
        String longKey = "a".repeat(60);
        UUID pid = UUID.randomUUID();
        assertThat(slotName(longKey, pid).length()).isLessThanOrEqualTo(63);
    }

    // ---------- dropSlotIfExists ----------

    @Test
    void skipsNonPostgresDataSource() {
        DatasourceEntity mariadb = datasource(DbType.MARIADB);
        UUID sourceDsId = mariadb.getId();
        when(datasourceRepository.findById(sourceDsId)).thenReturn(Optional.of(mariadb));

        cleaner.dropSlotIfExists(sourceDsId, "team-a", UUID.randomUUID());

        verifyNoInteractions(secretStore, dataSourceFactory);
    }

    @Test
    void skipsWhenDatasourceNotFound() {
        UUID missing = UUID.randomUUID();
        when(datasourceRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatCode(() -> cleaner.dropSlotIfExists(missing, "team-a", UUID.randomUUID()))
                .doesNotThrowAnyException();
        verifyNoInteractions(secretStore, dataSourceFactory);
    }

    @Test
    void dropsSlotForPostgresSource() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        UUID pipelineId = UUID.randomUUID();
        when(datasourceRepository.findById(pg.getId())).thenReturn(Optional.of(pg));
        when(secretStore.resolve(anyString())).thenReturn(new DbCredential("user", "pass"));
        when(dataSourceFactory.create(pg, "pass", false)).thenReturn(hikariDs);
        when(hikariDs.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(hikariDs.isClosed()).thenReturn(false);

        cleaner.dropSlotIfExists(pg.getId(), "team-a", pipelineId);

        verify(preparedStatement).setString(1, slotName("team-a", pipelineId));
        verify(preparedStatement).execute();
        verify(hikariDs).close();
    }

    @Test
    void doesNotThrowWhenDropFails() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        UUID pipelineId = UUID.randomUUID();
        when(datasourceRepository.findById(pg.getId())).thenReturn(Optional.of(pg));
        when(secretStore.resolve(anyString())).thenReturn(new DbCredential("user", "pass"));
        when(dataSourceFactory.create(pg, "pass", false)).thenReturn(hikariDs);
        when(hikariDs.getConnection()).thenThrow(new RuntimeException("connection refused"));
        when(hikariDs.isClosed()).thenReturn(false);

        assertThatCode(() -> cleaner.dropSlotIfExists(pg.getId(), "team-a", pipelineId))
                .doesNotThrowAnyException();
    }

    // ---------- helper ----------

    private static DatasourceEntity datasource(DbType type) {
        DatasourceEntity e = new DatasourceEntity();
        e.setId(UUID.randomUUID());
        e.setDbType(type);
        e.setHost("localhost");
        e.setPort(5432);
        e.setDbName("testdb");
        e.setUsername("app");
        e.setSecretRef("secret://test");
        return e;
    }
}
