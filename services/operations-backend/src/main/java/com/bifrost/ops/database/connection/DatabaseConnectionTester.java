package com.bifrost.ops.database.connection;

import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.global.common.datasource.DbType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 입력 연결 정보로 동적 HikariCP DataSource를 만들어 {@code SELECT 1}을 실행하는 연결 테스트
 * (database-registry.md §2, FR-014).
 *
 * <p>등록 전 raw 입력(아직 {@code DatasourceEntity}·{@code secret_ref} 없음)을 받으므로
 * 엔티티 기반 {@code DatabaseInspector}와 분리한다. 실패는 예외로 전파하지 않고
 * {@link DbConnectionFailureReason} 5종으로 분류해 {@link ConnectionTestResponse}로 반환한다.
 *
 * <p>자격증명(password)은 로그·응답에 남기지 않는다.
 */
@Component
public class DatabaseConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionTester.class);
    private static final long TIMEOUT_MS = 5000;
    private static final int MAX_CAUSE_DEPTH = 20;

    /** 연결을 시도하고 결과를 분류해 반환한다(예외를 던지지 않는다). */
    public ConnectionTestResponse test(DbType engine, String host, int port, String dbName,
                                       String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JdbcUrls.build(engine, host, port, dbName));
        config.setUsername(user);
        config.setPassword(password);
        config.setConnectionTimeout(TIMEOUT_MS);   // 단일 시도를 5초로 제한
        config.setMaximumPoolSize(1);              // 테스트용 최소 풀
        config.setInitializationFailTimeout(1);    // 첫 시도 실패 시 즉시 throw(재시도 루프 방지)
        config.setPoolName("db-conn-test");

        long start = System.nanoTime();
        try (HikariDataSource ds = new HikariDataSource(config);
             Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return ConnectionTestResponse.ok(elapsedMs(start));
        } catch (Exception e) {
            DbConnectionFailureReason reason = classify(e);
            // 값(자격증명)은 남기지 않고 분류·대상만 기록
            log.debug("연결 테스트 실패: engine={}, host={}, port={}, db={}, reason={}",
                    engine, host, port, dbName, reason);
            return ConnectionTestResponse.fail(reason, elapsedMs(start));
        }
    }

    /**
     * 예외를 5종으로 분류한다(단일 출처). HikariCP가 실패를 SQLTransientConnectionException 등으로
     * 감싸므로 원인 체인 전체를 본다. 가장 구체적인 SQLState/벤더코드(인증·DB 부재)를 먼저 판정하고,
     * 그다음 네트워크 레벨(거부·타임아웃), 마지막으로 메시지 휴리스틱을 적용한다.
     */
    DbConnectionFailureReason classify(Throwable e) {
        boolean sawTimeout = false;
        boolean sawRefused = false;

        // 원인 체인을 한 번 훑으며 (a) 네트워크 신호와 (b) 모든 SQLException의 SQLState/벤더코드를 본다.
        // HikariCP 래퍼(SQLState=null)가 체인 앞쪽에 올 수 있으므로 "첫" SQLException이 아니라
        // 결정적 코드를 가진 SQLException을 찾는다(가장 구체적인 판정 우선).
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof SocketTimeoutException) sawTimeout = true;
            if (t instanceof ConnectException || t instanceof UnknownHostException) sawRefused = true;
            if (t instanceof SQLException s) {
                String state = s.getSQLState();
                int vendor = s.getErrorCode();
                if (state != null) {
                    if (state.startsWith("28")) return DbConnectionFailureReason.AUTH_FAILED;   // invalid authorization
                    if (state.equals("3D000")) return DbConnectionFailureReason.DB_NOT_FOUND;    // PG invalid_catalog_name
                }
                if (vendor == 1045) return DbConnectionFailureReason.AUTH_FAILED;                // MariaDB access denied
                if (vendor == 1044 || vendor == 1049) return DbConnectionFailureReason.DB_NOT_FOUND; // MariaDB no db / unknown db
            }
        }

        // 2) 네트워크 레벨
        if (sawRefused) return DbConnectionFailureReason.CONNECTION_REFUSED;
        if (sawTimeout) return DbConnectionFailureReason.TIMEOUT;

        // 3) 메시지 휴리스틱(드라이버별 차이 보완)
        String msg = rootMessage(e).toLowerCase();
        if (msg.contains("connection refused")) return DbConnectionFailureReason.CONNECTION_REFUSED;
        if (msg.contains("timed out") || msg.contains("timeout")) return DbConnectionFailureReason.TIMEOUT;
        if (msg.contains("password authentication failed") || msg.contains("access denied"))
            return DbConnectionFailureReason.AUTH_FAILED;
        if (msg.contains("does not exist") || msg.contains("unknown database"))
            return DbConnectionFailureReason.DB_NOT_FOUND;

        return DbConnectionFailureReason.UNKNOWN;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t.getCause() != null && t.getCause() != t && depth < MAX_CAUSE_DEPTH; depth++) {
            t = t.getCause();
        }
        return t.getMessage() == null ? "" : t.getMessage();
    }
}
