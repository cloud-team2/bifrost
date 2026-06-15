package com.bifrost.ops.database.inspector.mariadb;

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
 * MariaDB Inspector 실구현.
 *
 * <p>CDC source 점검 항목: log_bin, binlog_format, binlog_row_image, server_id, REPLICATION·RELOAD 전역 권한.
 * <p>CDC sink 점검 항목: CREATE/INSERT/UPDATE/DELETE 권한.
 */
public class MariaDBInspector implements DatabaseInspector {

    private final HikariDataSource dataSource;
    private final String dbName;

    public MariaDBInspector(DatasourceEntity datasource, String password,
                             DynamicDataSourceFactory factory) {
        this.dataSource = factory.create(datasource, password, false);
        this.dbName = datasource.getDbName();
    }

    @Override
    public DbType getType() {
        return DbType.MARIADB;
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
            Map<String, Long> status = globalStatus(conn);
            long commits = status.getOrDefault("Com_commit", 0L);
            long rollbacks = status.getOrDefault("Com_rollback", 0L);
            long uptime = Math.max(1L, status.getOrDefault("Uptime", 1L));
            double tps = (double) (commits + rollbacks) / uptime;
            int activeConnections = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                    Math.max(0L, status.getOrDefault("Threads_connected", 0L))));
            return new MetricsSnapshot(round(tps), round(queryMs), null, activeConnections);
        } catch (SQLException e) {
            throw new RuntimeException("DB metrics 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public CdcReadinessResponse checkSourceReadiness() {
        try (Connection conn = dataSource.getConnection()) {
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

            boolean replication = hasGlobalPrivilege(conn, "REPLICATION SLAVE");
            checks.add(CdcCheck.of("REPLICATION 권한", replication ? OK : BLOCKED,
                    String.valueOf(replication), "REPLICATION SLAVE (전역 ON *.*)",
                    "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '<user>';"));

            // Debezium 스냅샷의 FLUSH TABLES WITH READ LOCK(전역 읽기 락)에 RELOAD 전역 권한이 필요하다(#457).
            // 없으면 등록 시엔 통과해도 런타임 스냅샷에서 'Access denied; need RELOAD'로 커넥터가 죽는다.
            boolean reload = hasGlobalPrivilege(conn, "RELOAD");
            checks.add(CdcCheck.of("RELOAD 권한", reload ? OK : BLOCKED,
                    String.valueOf(reload), "RELOAD (전역 ON *.*)",
                    "GRANT RELOAD ON *.* TO '<user>'; (스냅샷 시 FLUSH TABLES WITH READ LOCK용)"));

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
            String grants = getAllGrants(conn);

            boolean canCreate = hasPrivilege(grants, "CREATE") || hasPrivilege(grants, "ALL PRIVILEGES");
            checks.add(CdcCheck.of("CREATE 권한 (auto.create)", canCreate ? OK : WARNING,
                    String.valueOf(canCreate), "true",
                    "GRANT CREATE ON " + dbName + ".* TO '<user>';"));

            boolean canInsert = hasPrivilege(grants, "INSERT") || hasPrivilege(grants, "ALL PRIVILEGES");
            checks.add(CdcCheck.of("INSERT 권한", canInsert ? OK : BLOCKED,
                    String.valueOf(canInsert), "true",
                    "GRANT INSERT ON " + dbName + ".* TO '<user>';"));

            boolean canUpdate = hasPrivilege(grants, "UPDATE") || hasPrivilege(grants, "ALL PRIVILEGES");
            checks.add(CdcCheck.of("UPDATE 권한", canUpdate ? OK : WARNING,
                    String.valueOf(canUpdate), "true",
                    "GRANT UPDATE ON " + dbName + ".* TO '<user>';"));

            boolean canDelete = hasPrivilege(grants, "DELETE") || hasPrivilege(grants, "ALL PRIVILEGES");
            checks.add(CdcCheck.of("DELETE 권한", canDelete ? OK : WARNING,
                    String.valueOf(canDelete), "true",
                    "GRANT DELETE ON " + dbName + ".* TO '<user>';"));

            return new CdcReadinessResponse(
                    CdcReadinessStatus.worst(checks.stream().map(CdcCheck::status).toList()), checks);
        } catch (SQLException e) {
            throw new RuntimeException("Sink readiness 점검 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean checkTableCDCReadiness(String schema, String table) {
        // MariaDB는 테이블별 replident 설정 없음 — 전역 binlog_row_image=FULL이면 충분
        try (Connection conn = dataSource.getConnection()) {
            String rowImage = showVariable(conn, "binlog_row_image");
            return "FULL".equalsIgnoreCase(rowImage);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec) {
        String pid = spec.pipelineId().toString().replace("-", "").substring(0, 8);
        int serverId = spec.pipelineId().hashCode() & 0x7fffffff;
        if (serverId == 0) serverId = 1;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("connector.class", "io.debezium.connector.mariadb.MariaDbConnector");
        props.put("topic.prefix", spec.topicPrefix());
        props.put("database.server.id", String.valueOf(serverId));
        props.put("database.include.list", dbName);
        props.put("schema.history.internal.kafka.topic", "schema-history." + spec.projectKey() + "." + pid);
        if (!spec.tables().isEmpty()) {
            String tableList = spec.tables().stream()
                    .map(t -> t.schema() + "." + t.name())
                    .collect(java.util.stream.Collectors.joining(","));
            props.put("table.include.list", tableList);
        }
        return new DebeziumConnectorConfig("io.debezium.connector.mariadb.MariaDbConnector", props);
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
                // MariaDB/MySQL은 database를 JDBC catalog(TABLE_CAT)으로 보고하고 TABLE_SCHEM은 NULL이다.
                // JDBC 메타데이터 호출은 catalog+null schema를 써야 하지만, 우리 모델의 schema 자리에는
                // database를 채워야 한다(빈 schema면 파이프라인 생성 @NotBlank 검증 실패, #444).
                String jdbcSchema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                if (isSystemSchema(jdbcSchema)) continue;
                List<ColumnInfo> cols = columns(md, catalog, jdbcSchema, name);
                boolean hasPk = cols.stream().anyMatch(ColumnInfo::isPrimaryKey);
                List<String> pkCols = cols.stream()
                        .filter(ColumnInfo::isPrimaryKey).map(ColumnInfo::name).toList();
                String schema = firstNonBlank(jdbcSchema, rs.getString("TABLE_CAT"), catalog);
                TableStats stats = tableStats(conn, schema, name);
                result.add(new TableInfo(schema, name, stats.approximateRows(), stats.totalSizeBytes(),
                        hasPk, cols, pkCols));
            }
        }
        return result;
    }

    private TableStats tableStats(Connection conn, String schema, String table) {
        String sql = """
                SELECT COALESCE(TABLE_ROWS, 0) AS rows,
                       COALESCE(DATA_LENGTH, 0) + COALESCE(INDEX_LENGTH, 0) AS bytes
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        String tableSchema = firstNonBlank(schema, dbName);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableSchema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TableStats(Math.max(0L, rs.getLong("rows")),
                            Math.max(0L, rs.getLong("bytes")));
                }
            }
        } catch (SQLException ignored) {
            // Schema listing should still succeed when size statistics are unavailable.
        }
        return new TableStats(0L, 0L);
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

    private static String showVariable(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE '" + name + "'")) {
            return rs.next() ? rs.getString("Value") : null;
        }
    }

    /**
     * 전역(ON *.*) 권한 보유 여부. RELOAD·REPLICATION SLAVE 등은 전역 권한이라 db 단위
     * {@code ALL PRIVILEGES ON db.*}로는 충족되지 않는다 — SHOW GRANTS에서 {@code ON *.*} 라인만 본다.
     * (기존 hasReplicationGrant는 db단위 ALL PRIVILEGES도 통과시켜 런타임에서야 실패했다, #457.)
     */
    private static boolean hasGlobalPrivilege(Connection conn, String privilege) throws SQLException {
        String p = privilege.toUpperCase();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW GRANTS")) {
            while (rs.next()) {
                String grant = rs.getString(1);
                if (grant == null) continue;
                String g = grant.toUpperCase();
                if (g.contains("ON *.*") && (g.contains(p) || g.contains("ALL PRIVILEGES"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String getAllGrants(Connection conn) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW GRANTS FOR current_user()")) {
            while (rs.next()) sb.append(rs.getString(1)).append("\n");
        }
        return sb.toString().toUpperCase();
    }

    private static boolean hasPrivilege(String grants, String privilege) {
        return grants.contains(privilege.toUpperCase());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s == null ? "0" : s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double measureSelectOneMs(Connection conn) throws SQLException {
        long start = System.nanoTime();
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
        }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static Map<String, Long> globalStatus(Connection conn) throws SQLException {
        Map<String, Long> values = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS")) {
            while (rs.next()) {
                String name = rs.getString(1);
                String raw = rs.getString(2);
                if (name == null || raw == null) {
                    continue;
                }
                if (!List.of("Com_commit", "Com_rollback", "Threads_connected", "Uptime").contains(name)) {
                    continue;
                }
                try {
                    values.put(name, Long.parseLong(raw));
                } catch (NumberFormatException ignored) {
                    values.put(name, 0L);
                }
            }
        }
        return values;
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        String s = schema.toLowerCase();
        return s.equals("information_schema") || s.equals("performance_schema")
                || s.equals("mysql") || s.equals("sys");
    }

    /** 첫 번째 비어있지 않은 값(null·공백 제외). 모두 비면 null. */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private record TableStats(long approximateRows, long totalSizeBytes) {}
}
