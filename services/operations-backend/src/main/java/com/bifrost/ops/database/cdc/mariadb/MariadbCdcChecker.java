package com.bifrost.ops.database.cdc.mariadb;

import com.bifrost.ops.database.cdc.CdcReadinessChecker;
import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import com.bifrost.ops.global.common.datasource.DbType;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static com.bifrost.ops.database.cdc.CdcReadinessStatus.BLOCKED;
import static com.bifrost.ops.database.cdc.CdcReadinessStatus.OK;
import static com.bifrost.ops.database.cdc.CdcReadinessStatus.WARNING;

/**
 * MariaDB CDC 준비도 점검(database-registry.md §4.4). row 기반 binlog 사용 가능 여부를 본다.
 */
@Component
public class MariadbCdcChecker implements CdcReadinessChecker {

    @Override
    public DbType engine() {
        return DbType.MARIADB;
    }

    @Override
    public List<CdcCheck> check(Connection conn) throws SQLException {
        List<CdcCheck> checks = new ArrayList<>();

        String logBin = showVariable(conn, "log_bin");
        checks.add(CdcCheck.of("Binary Log", "ON".equalsIgnoreCase(logBin) ? OK : BLOCKED,
                logBin, "ON", "서버 설정에 log_bin=ON(--log-bin) 적용 후 재시작."));

        String format = showVariable(conn, "binlog_format");
        checks.add(CdcCheck.of("Binlog Format", "ROW".equalsIgnoreCase(format) ? OK : BLOCKED,
                format, "ROW", "SET GLOBAL binlog_format = ROW; (또는 설정 파일)"));

        String rowImage = showVariable(conn, "binlog_row_image");
        CdcReadinessStatus rowImageStatus = "FULL".equalsIgnoreCase(rowImage) ? OK : WARNING;
        checks.add(CdcCheck.of("Binlog Row Image", rowImageStatus, rowImage, "FULL",
                "SET GLOBAL binlog_row_image = FULL;"));

        int serverId = parseInt(showVariable(conn, "server_id"));
        checks.add(CdcCheck.of("Server ID", serverId > 0 ? OK : BLOCKED,
                String.valueOf(serverId), "> 0", "server_id를 0보다 큰 고유 값으로 설정."));

        boolean replication = hasReplicationGrant(conn);
        checks.add(CdcCheck.of("REPLICATION 권한", replication ? OK : BLOCKED,
                String.valueOf(replication), "REPLICATION SLAVE 포함",
                "GRANT REPLICATION SLAVE ON *.* TO '<user>';"));

        return checks;
    }

    /** {@code SHOW VARIABLES LIKE '<name>'} (name은 내부 상수). Value 컬럼을 읽는다. */
    private static String showVariable(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE '" + name + "'")) {
            return rs.next() ? rs.getString("Value") : null;
        }
    }

    private static boolean hasReplicationGrant(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW GRANTS")) {
            while (rs.next()) {
                String grant = rs.getString(1);
                if (grant != null) {
                    String g = grant.toUpperCase();
                    if (g.contains("REPLICATION SLAVE") || g.contains("ALL PRIVILEGES")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
