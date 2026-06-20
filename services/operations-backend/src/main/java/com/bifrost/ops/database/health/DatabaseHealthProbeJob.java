package com.bifrost.ops.database.health;

import com.bifrost.ops.database.connection.DatabaseConnectionTester;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

/**
 * DB 연결 헬스 주기 프로브(#179) + 복제지연 수집(S1).
 *
 * <p>등록된 모든 datasource에 대해 주기적으로 실제 연결을 시도해 {@code connection_status}를 갱신한다.
 * 이는 등록 시점 1회 검사인 cdc/sink readiness와 달리 '지금 연결되는가'를 라이브로 반영하므로,
 * DB가 죽으면(연결 거부) 곧바로 UNREACHABLE로 표시된다. 트래픽이 없어 커넥터가 아직 실패를
 * 모르는 상황도 프로브가 직접 잡는다.
 */
@Component
public class DatabaseHealthProbeJob {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthProbeJob.class);
    public static final String HEALTHY = "HEALTHY";
    public static final String UNREACHABLE = "UNREACHABLE";
    private static final int ERR_MAX = 255;
    private static final long PG_LAG_WARN_BYTES = 104_857_600L; // 100 MB
    private static final long MARIADB_LAG_WARN_SEC = 30L;

    private final DatasourceRepository datasourceRepository;
    private final DatabaseConnectionTester connectionTester;
    private final SecretStore secretStore;
    private final com.bifrost.ops.pipeline.PipelineStatusService pipelineStatusService;
    private final DynamicDataSourceFactory dataSourceFactory;
    private final EventService eventService;

    public DatabaseHealthProbeJob(DatasourceRepository datasourceRepository,
                                  DatabaseConnectionTester connectionTester,
                                  SecretStore secretStore,
                                  com.bifrost.ops.pipeline.PipelineStatusService pipelineStatusService,
                                  DynamicDataSourceFactory dataSourceFactory,
                                  EventService eventService) {
        this.datasourceRepository = datasourceRepository;
        this.connectionTester = connectionTester;
        this.secretStore = secretStore;
        this.pipelineStatusService = pipelineStatusService;
        this.dataSourceFactory = dataSourceFactory;
        this.eventService = eventService;
    }

    /**
     * 기본 60초마다 등록된 모든 DB의 연결 상태를 프로브한다.
     *
     * <p>의도적으로 {@code @Transactional}을 두지 않는다(#918). 이 메서드를 한 트랜잭션으로 감싸면,
     * {@code probe()} 내부의 중첩 {@code @Transactional}({@code reevaluateForDatasource} 등)이
     * 예외로 트랜잭션을 rollback-only로 마킹했을 때, 아래 try/catch로 예외를 삼켜도 플래그가 남아
     * 메서드 커밋 시점에 {@code UnexpectedRollbackException}이 발생한다. 트랜잭션 경계를 두지 않으면
     * 각 {@code save}/{@code reevaluateForDatasource}가 자기 트랜잭션에서 독립 커밋/롤백되어
     * 한 datasource의 실패가 전체 스윕을 깨지 않고, 상태 저장도 유실되지 않는다.
     */
    @Scheduled(fixedDelayString = "${database.health-probe-ms:60000}")
    public void probeAll() {
        for (DatasourceEntity e : datasourceRepository.findAll()) {
            try {
                probe(e);
            } catch (RuntimeException ex) {
                log.debug("DB 헬스 프로브 실패(무시): id={}, cause={}", e.getId(), ex.getMessage());
            }
        }
    }

    private void probe(DatasourceEntity e) {
        String prev = e.getConnectionStatus();
        String status;
        String error = null;
        try {
            DbCredential cred = secretStore.resolve(e.getSecretRef());
            ConnectionTestResponse r = connectionTester.test(
                    e.getDbType(), e.getHost(), e.getPort(), e.getDbName(), cred.user(), cred.password());
            if (r.success()) {
                status = HEALTHY;
            } else {
                status = UNREACHABLE;
                error = r.message();
            }
        } catch (RuntimeException ex) {
            // 자격증명 해석 실패 등 접근 자체 불가 → 연결 불가로 본다.
            status = UNREACHABLE;
            error = "연결 확인 실패: " + ex.getClass().getSimpleName();
        }
        e.setConnectionStatus(status);
        e.setConnectionError(truncate(error));
        e.setConnectionCheckedAt(Instant.now());
        datasourceRepository.save(e);

        // 연결 상태가 바뀌면(죽음/복구) 이 DB를 쓰는 파이프라인 상태를 재평가(#179).
        // source DB가 죽어도 Debezium은 retry로 RUNNING을 유지해 커넥터 이벤트가 안 오므로 이 경로가 필요.
        if (!status.equals(prev)) {
            try {
                pipelineStatusService.reevaluateForDatasource(e.getId());
            } catch (RuntimeException ex) {
                log.debug("DB 헬스 변화 후 파이프라인 재평가 실패(무시): id={}, cause={}", e.getId(), ex.getMessage());
            }
        }
    }

    /** 30초마다 HEALTHY datasource의 복제지연을 확인해 임계 초과 시 event를 발행한다(S1). */
    @Scheduled(fixedRate = 30_000, initialDelay = 45_000)
    @Transactional(readOnly = true)
    public void probeReplicationLag() {
        for (DatasourceEntity e : datasourceRepository.findAll()) {
            if (!HEALTHY.equals(e.getConnectionStatus())) continue;
            // (#734) 복제지연(MariaDB SHOW SLAVE STATUS 등)은 REPLICATION/SLAVE MONITOR 권한이 필요하다.
            // CDC 소스 준비가 OK가 아닌 datasource(예: 그 권한이 없는 sink 전용 MariaDB)는 1227 access
            // denied만 반복되므로 스킵한다. 복제지연은 소스(CDC) DB에서만 의미가 있다.
            if (!"OK".equalsIgnoreCase(e.getCdcReadinessStatus())) continue;
            try {
                checkReplicationLag(e);
            } catch (RuntimeException ex) {
                log.debug("복제지연 프로브 실패(무시): id={} cause={}", e.getId(), ex.getMessage());
            }
        }
    }

    private void checkReplicationLag(DatasourceEntity e) {
        DbCredential cred = secretStore.resolve(e.getSecretRef());
        if (e.getDbType() == DbType.POSTGRESQL) {
            checkPostgresLag(e, cred.password());
        } else if (e.getDbType() == DbType.MARIADB) {
            checkMariaDbLag(e, cred.password());
        }
    }

    private void checkPostgresLag(DatasourceEntity e, String password) {
        try (HikariDataSource ds = dataSourceFactory.create(e, password, true);
             Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)" +
                     " FROM pg_replication_slots WHERE active")) {
            while (rs.next()) {
                long lagBytes = rs.getLong(1);
                if (lagBytes > PG_LAG_WARN_BYTES) {
                    eventService.record(e.getTenantId(), null, EventLevel.WARN,
                            "DB_REPLICATION_LAG_WARNING",
                            "PostgreSQL 복제지연 경고: " + lagBytes / 1024 / 1024 + "MB (id=" + e.getId() + ")");
                }
            }
        } catch (Exception ex) {
            log.debug("PG 복제지연 쿼리 실패(무시): id={} cause={}", e.getId(), ex.getMessage());
        }
    }

    private void checkMariaDbLag(DatasourceEntity e, String password) {
        try (HikariDataSource ds = dataSourceFactory.create(e, password, true);
             Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW SLAVE STATUS")) {
            if (rs.next()) {
                Object behind = rs.getObject("Seconds_Behind_Master");
                if (behind != null) {
                    long lagSec = ((Number) behind).longValue();
                    if (lagSec > MARIADB_LAG_WARN_SEC) {
                        eventService.record(e.getTenantId(), null, EventLevel.WARN,
                                "DB_REPLICATION_LAG_WARNING",
                                "MariaDB 복제지연 경고: " + lagSec + "초 (id=" + e.getId() + ")");
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("MariaDB 복제지연 쿼리 실패(무시): id={} cause={}", e.getId(), ex.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERR_MAX ? s : s.substring(0, ERR_MAX);
    }
}
