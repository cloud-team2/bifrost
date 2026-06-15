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
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

import static com.bifrost.ops.pipeline.PostgresReplicationSlotCleaner.slotName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresReplicationSlotCleanerTest {

    @Mock private DatasourceRepository datasourceRepository;
    @Mock private SecretStore secretStore;
    @Mock private DynamicDataSourceFactory dataSourceFactory;
    @Mock private HikariDataSource hikariDs;
    @Mock private Connection connection;
    @Mock private PreparedStatement statePs;   // querySlotState 용
    @Mock private PreparedStatement dropPs;    // pg_drop_replication_slot 용
    @Mock private ResultSet stateRs;

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
        assertThat(slotName("my.project/key", pid)).matches("[a-z0-9_]+");
    }

    @Test
    void slotNameTruncatesAt63() {
        assertThat(slotName("a".repeat(60), UUID.randomUUID()).length()).isLessThanOrEqualTo(63);
    }

    // ---------- skip 조건 ----------

    @Test
    void skipsNonPostgresDataSource() {
        DatasourceEntity mariadb = datasource(DbType.MARIADB);
        when(datasourceRepository.findById(mariadb.getId())).thenReturn(Optional.of(mariadb));

        cleaner.dropSlotIfExists(mariadb.getId(), "team-a", UUID.randomUUID());

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

    // ---------- 정상 drop (slot inactive) ----------

    @Test
    void dropsSlotWhenInactive() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        UUID pipelineId = UUID.randomUUID();
        String slot = slotName("team-a", pipelineId);

        wireDs(pg);
        // querySlotState → INACTIVE
        when(connection.prepareStatement(contains("pg_replication_slots"))).thenReturn(statePs);
        when(statePs.executeQuery()).thenReturn(stateRs);
        when(stateRs.next()).thenReturn(true);
        when(stateRs.getBoolean("active")).thenReturn(false);
        // drop
        when(connection.prepareStatement(contains("pg_drop_replication_slot"))).thenReturn(dropPs);

        cleaner.dropSlotIfExists(pg.getId(), "team-a", pipelineId);

        verify(statePs).setString(1, slot);
        verify(dropPs).setString(1, slot);
        verify(dropPs).execute();
        verify(hikariDs).close();
    }

    // ---------- slot 없음 (GONE) — drop 호출 안 함 ----------

    @Test
    void skipsDropWhenSlotGone() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        wireDs(pg);
        when(connection.prepareStatement(contains("pg_replication_slots"))).thenReturn(statePs);
        when(statePs.executeQuery()).thenReturn(stateRs);
        when(stateRs.next()).thenReturn(false); // slot 없음

        cleaner.dropSlotIfExists(pg.getId(), "team-a", UUID.randomUUID());

        verify(connection, never()).prepareStatement(contains("pg_drop_replication_slot"));
    }

    // ---------- retry: active → inactive 전환 후 drop 성공 ----------

    @Test
    void retriesUntilSlotBecomesInactive() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        UUID pipelineId = UUID.randomUUID();

        wireDs(pg);
        when(connection.prepareStatement(contains("pg_replication_slots"))).thenReturn(statePs);
        when(statePs.executeQuery()).thenReturn(stateRs);
        when(stateRs.next()).thenReturn(true);
        // 1~2회: active, 3회: inactive
        when(stateRs.getBoolean("active")).thenReturn(true, true, false);
        when(connection.prepareStatement(contains("pg_drop_replication_slot"))).thenReturn(dropPs);

        cleaner.dropSlotIfExists(pg.getId(), "team-a", pipelineId);

        // querySlotState 3회 호출 (active×2 → inactive×1)
        verify(statePs, times(3)).executeQuery();
        verify(dropPs).execute();
    }

    // ---------- 최대 재시도 초과 — drop 포기 ----------

    @Test
    void givesUpAfterMaxAttemptsWhenAlwaysActive() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        wireDs(pg);
        when(connection.prepareStatement(contains("pg_replication_slots"))).thenReturn(statePs);
        when(statePs.executeQuery()).thenReturn(stateRs);
        when(stateRs.next()).thenReturn(true);
        when(stateRs.getBoolean("active")).thenReturn(true); // 항상 active

        assertThatCode(() -> cleaner.dropSlotIfExists(pg.getId(), "team-a", UUID.randomUUID()))
                .doesNotThrowAnyException();

        // drop은 한 번도 호출되지 않아야 한다
        verify(connection, never()).prepareStatement(contains("pg_drop_replication_slot"));
    }

    // ---------- 연결 실패 — 예외 미전파 ----------

    @Test
    void doesNotThrowWhenConnectionFails() throws Exception {
        DatasourceEntity pg = datasource(DbType.POSTGRESQL);
        wireDs(pg);
        when(hikariDs.getConnection()).thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> cleaner.dropSlotIfExists(pg.getId(), "team-a", UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    // ---------- helpers ----------

    private void wireDs(DatasourceEntity pg) throws Exception {
        when(datasourceRepository.findById(pg.getId())).thenReturn(Optional.of(pg));
        when(secretStore.resolve(anyString())).thenReturn(new DbCredential("user", "pass"));
        when(dataSourceFactory.create(pg, "pass", false)).thenReturn(hikariDs);
        when(hikariDs.getConnection()).thenReturn(connection);
        when(hikariDs.isClosed()).thenReturn(false);
    }

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
