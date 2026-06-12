package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.SqlReadResult;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent read 프리미티브 — datasource에 read-only SELECT 실행(#633).
 *
 * <p>'데이터베이스 상세'(스키마·행수·실데이터) 질의를 도구를 일일이 추가하지 않고도 답하게 하는
 * 범용 프리미티브. 안전장치: ① SELECT/WITH 로 시작하는 단일 statement 만 허용(DDL/DML/멀티문 거부),
 * ② readOnly HikariDataSource, ③ 행수·시간 상한.
 */
@RestController
public class InternalOpsSqlController {

    private static final int MAX_ROWS = 200;
    private static final int QUERY_TIMEOUT_SEC = 5;
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|merge|call|do|"
            + "vacuum|analyze|lock|set|reset|begin|commit|rollback|comment|reindex)\\b",
            Pattern.CASE_INSENSITIVE);

    private final WorkspaceRepository workspaceRepository;
    private final DatasourceRepository datasourceRepository;
    private final SecretStore secretStore;
    private final DynamicDataSourceFactory dataSourceFactory;

    public InternalOpsSqlController(WorkspaceRepository workspaceRepository,
                                    DatasourceRepository datasourceRepository,
                                    SecretStore secretStore,
                                    DynamicDataSourceFactory dataSourceFactory) {
        this.workspaceRepository = workspaceRepository;
        this.datasourceRepository = datasourceRepository;
        this.secretStore = secretStore;
        this.dataSourceFactory = dataSourceFactory;
    }

    public record SqlReadRequest(String sql) {}

    /** sql_read — datasource에 read-only SELECT 실행(최대 200행). */
    @PostMapping("/internal/ops/projects/{projectId}/datasources/{datasourceId}/query")
    public ResponseEntity<OpsEnvelope<SqlReadResult>> query(
            @PathVariable String projectId,
            @PathVariable String datasourceId,
            @RequestBody SqlReadRequest body,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            String sql = body == null ? null : body.sql();
            assertReadOnly(sql);

            WorkspaceEntity ws = WorkspaceLookup.resolve(workspaceRepository, projectId)
                    .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                            "프로젝트를 찾을 수 없습니다: " + projectId));
            DatasourceEntity ds = datasourceRepository
                    .findByIdAndTenantId(UUID.fromString(datasourceId), ws.getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                            "datasource를 찾을 수 없습니다: " + datasourceId));

            SqlReadResult result = execute(ds, sql.trim());
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "sql_read", result));
        } catch (ApiException e) {
            return ResponseEntity.ok(OpsEnvelope.error(requestId, "sql_read", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(OpsEnvelope.error(requestId, "sql_read",
                    "쿼리 실행 실패: " + e.getMessage()));
        }
    }

    private static void assertReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "sql이 비어 있습니다");
        }
        String trimmed = sql.trim();
        String noTrailingSemicolon = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (noTrailingSemicolon.contains(";")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "멀티 statement는 허용되지 않습니다(SELECT 1건만)");
        }
        String lower = noTrailingSemicolon.toLowerCase();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "SELECT/WITH 쿼리만 허용됩니다(read-only)");
        }
        if (FORBIDDEN.matcher(noTrailingSemicolon).find()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "쓰기/DDL 키워드가 포함돼 거부되었습니다(read-only)");
        }
    }

    private SqlReadResult execute(DatasourceEntity ds, String sql) throws Exception {
        DbCredential cred = secretStore.resolve(ds.getSecretRef());
        try (HikariDataSource pool = dataSourceFactory.create(ds, cred.password(), true);
             Connection conn = pool.getConnection();
             Statement st = conn.createStatement()) {
            st.setMaxRows(MAX_ROWS + 1);
            st.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                List<String> columns = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) columns.add(meta.getColumnLabel(i));

                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= MAX_ROWS) { truncated = true; break; }
                    List<String> row = new ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++) {
                        Object v = rs.getObject(i);
                        row.add(v == null ? null : String.valueOf(v));
                    }
                    rows.add(row);
                }
                return new SqlReadResult(columns, rows, rows.size(), truncated);
            }
        }
    }
}
