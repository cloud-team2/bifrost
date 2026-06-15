package com.bifrost.ops.database.inspector.postgres;

import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.DebeziumConnectorConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.bifrost.ops.database.cdc.CdcReadinessStatus.*;

/**
 * PostgreSQL Inspector 실구현.
 *
 * <p>CDC source 점검 항목: wal_level, max_wal_senders, max_replication_slots, REPLICATION 권한, publication.
 * <p>CDC sink 점검 항목: schema USAGE/CREATE 권한, INSERT/UPDATE/DELETE 가능 여부.
 */
public class PostgresInspector implements DatabaseInspector {

    private final HikariDataSource dataSource;

    public PostgresInspector(DatasourceEntity datasource, String password,
                              DynamicDataSourceFactory factory) {
        this.dataSource = factory.create(datasource, password, false);
    }

    @Override
    public DbType getType() {
        return DbType.POSTGRESQL;
    }

    @Override
    public ConnectionTestResult testConnection() {
        Instant start = Instant.now();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return new ConnectionTestResult(true, "연결 성공", Duration.between(start, Instant.now()));
        } catch (SQLException e) {
            return new ConnectionTestResult(false, e.getMessage(), Duration.between(start, Instant.now()));
        }
    }

    @Override
    public List<TableInfo> listTables() {
        try (Connection conn = dataSource.getConnection()) {
            return readTables(conn);
        } catch (SQLException e) {
            throw new RuntimeException("테이블 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public MetricsSnapshot collectMetrics() {
        try (Connection conn = dataSource.getConnection()) {
            double queryMs = measureSelectOneMs(conn);
            double tps = queryDouble(conn, """
                    SELECT (xact_commit + xact_rollback)::double precision
                           / GREATEST(EXTRACT(EPOCH FROM now() - COALESCE(stats_reset, pg_postmaster_start_time())), 1)
                    FROM pg_stat_database
                    WHERE datname = current_database()
                    """);
            int activeConnections = (int) queryLong(conn,
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()");
            return new MetricsSnapshot(round(tps), round(queryMs), null, Math.max(0, activeConnections));
        } catch (SQLException e) {
            throw new RuntimeException("DB metrics 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public CdcReadinessResponse checkSourceReadiness() {
        try (Connection conn = dataSource.getConnection()) {
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
            // slot이 가득 찼으면 BLOCKED: 파이프라인 생성 시 커넥터 task가 즉시 실패하기 때문이다(#685).
            CdcReadinessStatus slotStatus = (maxSlots > 0 && usedSlots >= maxSlots) ? BLOCKED : OK;
            checks.add(CdcCheck.of("Replication Slot 여유", slotStatus,
                    usedSlots + "/" + maxSlots, "< max_replication_slots",
                    "사용하지 않는 replication slot을 정리하세요 (pg_drop_replication_slot)."));

            boolean rolReplication = queryBool(conn,
                    "SELECT rolreplication FROM pg_roles WHERE rolname = current_user");
            checks.add(CdcCheck.of("REPLICATION 권한", rolReplication ? OK : BLOCKED,
                    String.valueOf(rolReplication), "true", "ALTER ROLE <user> REPLICATION;"));

            boolean pubExists = queryLong(conn, "SELECT count(*) FROM pg_publication") > 0;
            boolean canCreate = queryBool(conn,
                    "SELECT has_database_privilege(current_database(), 'CREATE')");
            CdcReadinessStatus pubStatus = (pubExists || canCreate) ? OK : WARNING;
            String pubActual = pubExists ? "존재" : (canCreate ? "생성 가능" : "없음·생성 불가");
            checks.add(CdcCheck.of("Publication (pgoutput)", pubStatus, pubActual, "존재 또는 생성 가능",
                    "publication 생성(CREATE) 권한 또는 기존 publication이 필요합니다."));

            return new CdcReadinessResponse(
                    CdcReadinessStatus.worst(checks.stream().map(CdcCheck::status).toList()), checks);
        } catch (SQLException e) {
            throw new RuntimeException("Source readiness 점검 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public CdcReadinessResponse checkSinkReadiness() {
        try (Connection conn = dataSource.getConnection()) {
            List<CdcCheck> checks = new ArrayList<>();

            boolean canUse = queryBool(conn,
                    "SELECT has_schema_privilege(current_user, 'public', 'USAGE')");
            checks.add(CdcCheck.of("Schema USAGE 권한", canUse ? OK : BLOCKED,
                    String.valueOf(canUse), "true",
                    "GRANT USAGE ON SCHEMA public TO <user>;"));

            boolean canCreate = queryBool(conn,
                    "SELECT has_schema_privilege(current_user, 'public', 'CREATE')");
            checks.add(CdcCheck.of("Schema CREATE 권한 (auto.create)", canCreate ? OK : WARNING,
                    String.valueOf(canCreate), "true",
                    "GRANT CREATE ON SCHEMA public TO <user>;"));

            boolean isSuperuser = queryBool(conn,
                    "SELECT rolsuper FROM pg_roles WHERE rolname = current_user");
            boolean hasInsert = isSuperuser || queryBool(conn,
                    "SELECT count(*) > 0 FROM information_schema.role_table_grants " +
                    "WHERE grantee = current_user AND privilege_type = 'INSERT'");
            checks.add(CdcCheck.of("쓰기 권한 (INSERT/UPDATE/DELETE)",
                    hasInsert ? OK : WARNING,
                    isSuperuser ? "superuser" : String.valueOf(hasInsert), "true",
                    "GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO <user>;"));

            return new CdcReadinessResponse(
                    CdcReadinessStatus.worst(checks.stream().map(CdcCheck::status).toList()), checks);
        } catch (SQLException e) {
            throw new RuntimeException("Sink readiness 점검 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean checkTableCDCReadiness(String schema, String table) {
        // relreplident: 'd'=default(PK 기반), 'f'=full — 둘 다 CDC 가능
        String sql = "SELECT relreplident FROM pg_class c " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE n.nspname = ? AND c.relname = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String ident = rs.getString(1);
                return "d".equals(ident) || "f".equals(ident);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec) {
        String slotName = slotName(spec.projectKey(), spec.pipelineId());
        Map<String, String> props = new LinkedHashMap<>();
        props.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.put("plugin.name", "pgoutput");
        props.put("topic.prefix", spec.topicPrefix());
        props.put("slot.name", slotName);
        props.put("publication.name", slotName + "_pub");
        if (!spec.tables().isEmpty()) {
            String tableList = spec.tables().stream()
                    .map(t -> t.schema() + "." + t.name())
                    .collect(java.util.stream.Collectors.joining(","));
            props.put("table.include.list", tableList);
        }
        return new DebeziumConnectorConfig("io.debezium.connector.postgresql.PostgresConnector", props);
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // ---- private helpers ----

    private List<TableInfo> readTables(Connection conn) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        String catalog = conn.getCatalog();
        List<TableInfo> result = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                if (isSystemSchema(schema)) continue;
                List<ColumnInfo> cols = columns(md, catalog, schema, name);
                boolean hasPk = cols.stream().anyMatch(ColumnInfo::isPrimaryKey);
                List<String> pkCols = cols.stream()
                        .filter(ColumnInfo::isPrimaryKey).map(ColumnInfo::name).toList();
                TableStats stats = tableStats(conn, schema, name);
                result.add(new TableInfo(schema, name, stats.approximateRows(), stats.totalSizeBytes(),
                        hasPk, cols, pkCols));
            }
        }
        return result;
    }

    private static TableStats tableStats(Connection conn, String schema, String table) {
        String sql = """
                SELECT c.reltuples AS rows,
                       pg_total_relation_size(c.oid) AS bytes
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double rowEstimate = rs.getDouble("rows");
                    Long rows = rs.wasNull() || rowEstimate < 0.0 ? null : Math.max(0L, (long) rowEstimate);
                    return new TableStats(rows, nullableNonNegativeLong(rs, "bytes"));
                }
            }
        } catch (SQLException ignored) {
            // Schema listing should still succeed; null marks stats unavailable instead of fake zero.
        }
        return new TableStats(null, null);
    }

    private static Long nullableNonNegativeLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Math.max(0L, value);
    }

    private List<ColumnInfo> columns(DatabaseMetaData md, String catalog,
                                     String schema, String table) throws SQLException {
        Set<String> pks = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
        }
        Set<String> indexed = new HashSet<>();
        try (ResultSet rs = md.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col != null) indexed.add(col);
            }
        }
        List<ColumnInfo> cols = new ArrayList<>();
        try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                cols.add(new ColumnInfo(
                        name,
                        rs.getString("TYPE_NAME"),
                        "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                        pks.contains(name),
                        indexed.contains(name)));
            }
        }
        return cols;
    }

    private static String showVar(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW " + name)) {
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

    private static double queryDouble(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? Math.max(0.0, rs.getDouble(1)) : 0.0;
        }
    }

    private static double measureSelectOneMs(Connection conn) throws SQLException {
        long start = System.nanoTime();
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
        }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s == null ? "0" : s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        String s = schema.toLowerCase();
        return s.equals("information_schema") || s.startsWith("pg_");
    }

    private static String slotName(String projectKey, java.util.UUID pipelineId) {
        String pid = pipelineId.toString().replace("-", "").substring(0, 8);
        String raw = "bif_" + projectKey + "_" + pid;
        return raw.toLowerCase().replaceAll("[^a-z0-9_]", "_")
                .substring(0, Math.min(63, raw.length()));
    }

    private record TableStats(Long approximateRows, Long totalSizeBytes) {}
}
