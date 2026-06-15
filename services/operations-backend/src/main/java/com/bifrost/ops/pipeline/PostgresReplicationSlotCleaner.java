package com.bifrost.ops.pipeline;

import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

/**
 * 파이프라인 삭제 시 PostgreSQL replication slot을 자동 drop한다(#684, #696).
 *
 * <p>Debezium은 KafkaConnector CR이 삭제되어도 connector가 생성한 replication slot을 drop하지 않는다.
 * slot이 누적되면 PostgreSQL의 {@code max_replication_slots} 한도(기본값 10)를 소진하여
 * 이후 파이프라인 생성 시 커넥터 task가 {@code PSQLException: all replication slots are in use}로 실패한다.
 *
 * <p>KafkaConnector CR 삭제 직후에는 Debezium 연결이 아직 살아있어 slot이 {@code active=true} 상태다.
 * {@code active} slot은 drop 불가이므로 {@link com.bifrost.ops.pipeline.kafka.KafkaResourceCleaner}의
 * sink group 삭제와 동일하게 inactive 전환까지 재시도한다(#696).
 *
 * <p>slot 이름은 {@link #slotName(String, UUID)}로 결정론적으로 계산한다 — {@code PostgresInspector}와 동일한 규칙.
 * <p>best-effort: 실패해도 예외를 던지지 않고 경고만 남긴다.
 */
@Component
public class PostgresReplicationSlotCleaner {

    private static final Logger log = LoggerFactory.getLogger(PostgresReplicationSlotCleaner.class);

    /** Debezium 연결이 종료되어 slot이 inactive로 전환될 때까지 기다리는 재시도 횟수·간격. */
    private static final int DROP_ATTEMPTS = 6;
    private static final long DROP_BACKOFF_MS = 1_200L;

    private final DatasourceRepository datasourceRepository;
    private final SecretStore secretStore;
    private final DynamicDataSourceFactory dataSourceFactory;

    public PostgresReplicationSlotCleaner(DatasourceRepository datasourceRepository,
                                          SecretStore secretStore,
                                          DynamicDataSourceFactory dataSourceFactory) {
        this.datasourceRepository = datasourceRepository;
        this.secretStore = secretStore;
        this.dataSourceFactory = dataSourceFactory;
    }

    /**
     * PostgreSQL source DB의 replication slot을 drop한다.
     * 비-PostgreSQL 또는 DB 조회 불가 시 조용히 반환한다.
     */
    public void dropSlotIfExists(UUID sourceDatasourceId, String projectKey, UUID pipelineId) {
        Optional<DatasourceEntity> opt = datasourceRepository.findById(sourceDatasourceId);
        if (opt.isEmpty()) {
            log.debug("replication slot 정리 생략 — datasource 없음: id={}", sourceDatasourceId);
            return;
        }
        DatasourceEntity entity = opt.get();
        if (entity.getDbType() != DbType.POSTGRESQL) {
            return;
        }
        String slot = slotName(projectKey, pipelineId);
        dropSlot(entity, slot);
    }

    private void dropSlot(DatasourceEntity entity, String slotName) {
        DbCredential cred;
        try {
            cred = secretStore.resolve(entity.getSecretRef());
        } catch (Exception e) {
            log.warn("replication slot 삭제 실패 — 자격증명 조회 오류: slotName={}, cause={}", slotName, e.getMessage());
            return;
        }
        HikariDataSource ds = null;
        try {
            ds = dataSourceFactory.create(entity, cred.password(), false);
            for (int attempt = 1; attempt <= DROP_ATTEMPTS; attempt++) {
                try (Connection conn = ds.getConnection()) {
                    SlotState state = querySlotState(conn, slotName);
                    if (state == SlotState.GONE) {
                        log.debug("replication slot 없음(이미 삭제됨): {}", slotName);
                        return;
                    }
                    if (state == SlotState.ACTIVE) {
                        // Debezium 연결이 아직 살아있음 — inactive 전환까지 대기 후 재시도.
                        if (attempt < DROP_ATTEMPTS) {
                            log.debug("replication slot active 상태, {}ms 후 재시도 (attempt {}/{})",
                                    DROP_BACKOFF_MS, attempt, DROP_ATTEMPTS);
                            sleep(DROP_BACKOFF_MS);
                            continue;
                        }
                        log.warn("replication slot 삭제 포기(active 지속): slotName={}", slotName);
                        return;
                    }
                    // SlotState.INACTIVE — drop 실행
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT pg_drop_replication_slot(?)")) {
                        ps.setString(1, slotName);
                        ps.execute();
                        log.info("replication slot 삭제 완료: {} (attempt {})", slotName, attempt);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("replication slot 삭제 실패(무시): slotName={}, cause={}", slotName, e.getMessage());
        } finally {
            if (ds != null && !ds.isClosed()) {
                ds.close();
            }
        }
    }

    private static SlotState querySlotState(Connection conn, String slotName) throws java.sql.SQLException {
        String sql = "SELECT active FROM pg_replication_slots WHERE slot_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slotName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return SlotState.GONE;
                return rs.getBoolean("active") ? SlotState.ACTIVE : SlotState.INACTIVE;
            }
        }
    }

    private enum SlotState { GONE, ACTIVE, INACTIVE }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Debezium slot 이름 계산 — {@code PostgresInspector.slotName()}과 동일한 규칙.
     * {@code bif_{projectKey}_{pipelineId_first8}} 정규화 후 63자 truncate.
     */
    static String slotName(String projectKey, UUID pipelineId) {
        String pid = pipelineId.toString().replace("-", "").substring(0, 8);
        String raw = "bif_" + projectKey + "_" + pid;
        String normalized = raw.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return normalized.substring(0, Math.min(63, normalized.length()));
    }
}
