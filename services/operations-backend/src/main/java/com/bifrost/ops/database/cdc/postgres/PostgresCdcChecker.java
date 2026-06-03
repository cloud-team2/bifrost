package com.bifrost.ops.database.cdc.postgres;

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
 * PostgreSQL CDC 준비도 점검(database-registry.md §4.3). logical decoding 사용 가능 여부를 본다.
 */
@Component
public class PostgresCdcChecker implements CdcReadinessChecker {

    @Override
    public DbType engine() {
        return DbType.POSTGRESQL;
    }

    @Override
    public List<CdcCheck> check(Connection conn) throws SQLException {
        List<CdcCheck> checks = new ArrayList<>();

        String walLevel = showVar(conn, "wal_level");
        checks.add(CdcCheck.of("WAL Level", "logical".equals(walLevel) ? OK : BLOCKED,
                walLevel, "logical",
                "ALTER SYSTEM SET wal_level = logical; SELECT pg_reload_conf(); (재시작 필요)"));

        int senders = parseInt(showVar(conn, "max_wal_senders"));
        checks.add(CdcCheck.of("Max WAL Senders", senders > 0 ? OK : BLOCKED,
                String.valueOf(senders), "> 0", "ALTER SYSTEM SET max_wal_senders = 10;"));

        int maxSlots = parseInt(showVar(conn, "max_replication_slots"));
        checks.add(CdcCheck.of("Max Replication Slots", maxSlots > 0 ? OK : BLOCKED,
                String.valueOf(maxSlots), "> 0", "ALTER SYSTEM SET max_replication_slots = 10;"));

        int usedSlots = (int) queryLong(conn, "SELECT count(*) FROM pg_replication_slots");
        CdcReadinessStatus slotStatus = (maxSlots > 0 && usedSlots >= maxSlots) ? WARNING : OK;
        checks.add(CdcCheck.of("Replication Slot 여유", slotStatus,
                usedSlots + "/" + maxSlots, "< max_replication_slots",
                "사용하지 않는 replication slot을 정리하세요 (pg_drop_replication_slot)."));

        boolean rolReplication =
                queryBool(conn, "SELECT rolreplication FROM pg_roles WHERE rolname = current_user");
        checks.add(CdcCheck.of("REPLICATION 권한", rolReplication ? OK : BLOCKED,
                String.valueOf(rolReplication), "true", "ALTER ROLE <user> REPLICATION;"));

        boolean pubExists = queryLong(conn, "SELECT count(*) FROM pg_publication") > 0;
        boolean canCreate =
                queryBool(conn, "SELECT has_database_privilege(current_database(), 'CREATE')");
        CdcReadinessStatus pubStatus = (pubExists || canCreate) ? OK : WARNING;
        String pubActual = pubExists ? "존재" : (canCreate ? "생성 가능" : "없음·생성 불가");
        checks.add(CdcCheck.of("Publication (pgoutput)", pubStatus, pubActual, "존재 또는 생성 가능",
                "publication 생성(CREATE) 권한 또는 기존 publication이 필요합니다."));

        return checks;
    }

    /** {@code SHOW <name>} (name은 내부 상수 — SQL 주입 아님). */
    private static String showVar(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW " + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static boolean queryBool(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() && rs.getBoolean(1);
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
