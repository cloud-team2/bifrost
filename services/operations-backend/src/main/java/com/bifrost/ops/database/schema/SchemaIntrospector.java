package com.bifrost.ops.database.schema;

import com.bifrost.ops.database.connection.JdbcUrls;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse.ColumnSchema;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse.TableSchema;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 등록된 DB의 스키마를 동적 연결로 읽는다(#28, FR-016). 엔진별 카탈로그 쿼리 대신
 * JDBC {@link DatabaseMetaData}를 써서 PostgreSQL·MariaDB를 동일 코드로 처리한다.
 *
 * <p>자격증명(password)은 호출부가 SecretStore에서 resolve해 넘긴다. 읽기 전용 연결을
 * 5초 timeout·풀 1로 짧게 열고 닫는다.
 */
@Component
public class SchemaIntrospector {

    private static final long TIMEOUT_MS = 5000;

    public DatabaseSchemaResponse introspect(DatasourceEntity ds, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(JdbcUrls.build(ds.getDbType(), ds.getHost(), ds.getPort(), ds.getDbName()));
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(password);
        cfg.setConnectionTimeout(TIMEOUT_MS);
        cfg.setMaximumPoolSize(1);
        cfg.setInitializationFailTimeout(1);
        cfg.setReadOnly(true);
        cfg.setPoolName("db-schema-introspect");

        try (HikariDataSource dataSource = new HikariDataSource(cfg);
             Connection conn = dataSource.getConnection()) {
            return read(conn);
        } catch (SQLException | RuntimeException e) {
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED, "스키마 조회 실패");
        }
    }

    private DatabaseSchemaResponse read(Connection conn) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        String catalog = conn.getCatalog();
        List<TableSchema> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                if (isSystemSchema(schema)) {
                    continue;
                }
                tables.add(new TableSchema(schema, table, columns(md, catalog, schema, table)));
            }
        }
        return new DatabaseSchemaResponse(tables);
    }

    private List<ColumnSchema> columns(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }
        Set<String> indexed = new HashSet<>();
        try (ResultSet rs = md.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col != null) {
                    indexed.add(col);
                }
            }
        }
        List<ColumnSchema> cols = new ArrayList<>();
        try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                cols.add(new ColumnSchema(name, type, nullable,
                        primaryKeys.contains(name), indexed.contains(name)));
            }
        }
        return cols;
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toLowerCase();
        return s.equals("information_schema") || s.startsWith("pg_");
    }
}
