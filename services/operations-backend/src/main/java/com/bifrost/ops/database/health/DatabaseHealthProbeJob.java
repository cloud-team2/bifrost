package com.bifrost.ops.database.health;

import com.bifrost.ops.database.connection.DatabaseConnectionTester;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DB 연결 헬스 주기 프로브(#179).
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

    private final DatasourceRepository datasourceRepository;
    private final DatabaseConnectionTester connectionTester;
    private final SecretStore secretStore;

    public DatabaseHealthProbeJob(DatasourceRepository datasourceRepository,
                                  DatabaseConnectionTester connectionTester,
                                  SecretStore secretStore) {
        this.datasourceRepository = datasourceRepository;
        this.connectionTester = connectionTester;
        this.secretStore = secretStore;
    }

    /** 기본 60초마다 등록된 모든 DB의 연결 상태를 프로브한다. */
    @Scheduled(fixedDelayString = "${database.health-probe-ms:60000}")
    @Transactional
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
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERR_MAX ? s : s.substring(0, ERR_MAX);
    }
}
