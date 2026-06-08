package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.pipeline.dto.SyncStatusResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * CDC(direct) 파이프라인의 동기화 상태 조회(#107, 상세 Sync 탭).
 *
 * <p>source/sink DB에 각각 {@code SELECT COUNT(*)}를 실행해 실제 행수를 비교한다. 자격증명은
 * {@link SecretStore}에서 resolve하고 읽기 전용 연결을 짧게 열고 닫는다({@link DynamicDataSourceFactory}).
 * 접속 실패·테이블 미존재(생성 중)는 -1로 반환해 화면이 "준비중"을 표시할 수 있게 한다.
 */
@Service
public class PipelineSyncService {

    private static final Logger log = LoggerFactory.getLogger(PipelineSyncService.class);

    private final PipelineRepository pipelineRepository;
    private final DatasourceRepository datasourceRepository;
    private final SecretStore secretStore;
    private final DynamicDataSourceFactory dataSourceFactory;
    private final WorkspaceAccessGuard accessGuard;

    public PipelineSyncService(PipelineRepository pipelineRepository,
                               DatasourceRepository datasourceRepository,
                               SecretStore secretStore,
                               DynamicDataSourceFactory dataSourceFactory,
                               WorkspaceAccessGuard accessGuard) {
        this.pipelineRepository = pipelineRepository;
        this.datasourceRepository = datasourceRepository;
        this.secretStore = secretStore;
        this.dataSourceFactory = dataSourceFactory;
        this.accessGuard = accessGuard;
    }

    public SyncStatusResponse syncStatus(UUID wsId, AuthenticatedUser principal, UUID pipelineId) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = pipelineRepository.findByIdAndTenantId(pipelineId, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND, "파이프라인을 찾을 수 없습니다"));
        if (p.getSinkDatasourceId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "sink가 없는 파이프라인은 동기화 상태가 없습니다");
        }

        DatasourceEntity source = requireDatasource(wsId, p.getSourceDatasourceId());
        DatasourceEntity sink = requireDatasource(wsId, p.getSinkDatasourceId());

        // source: 등록된 schema.table / sink: 동일 테이블명(RegexRouter로 토픽→테이블명 축약), 기본 schema
        long sourceRows = countRows(source, p.getSchemaName(), p.getTableName());
        long sinkRows = countRows(sink, null, p.getTableName());
        long delta = (sourceRows < 0 || sinkRows < 0) ? -1 : sourceRows - sinkRows;
        return new SyncStatusResponse(sourceRows, sinkRows, delta, Instant.now());
    }

    private DatasourceEntity requireDatasource(UUID wsId, UUID dbId) {
        return datasourceRepository.findByIdAndTenantId(dbId, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND, "데이터베이스를 찾을 수 없습니다"));
    }

    /** {@code SELECT COUNT(*)} 실행. 접속 실패·테이블 미존재는 -1. */
    private long countRows(DatasourceEntity ds, String schema, String table) {
        String from = qualifiedTable(ds.getDbType(), schema, table);
        String password = secretStore.resolve(ds.getSecretRef()).password();
        try (HikariDataSource dataSource = dataSourceFactory.create(ds, password, true);
             Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + from)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            log.debug("행수 조회 실패: db={}, table={}, cause={}", ds.getId(), from, e.toString());
            return -1L;
        }
    }

    /** 엔진별 식별자 인용. schema/table은 파이프라인 생성 시 검증된 값. */
    private String qualifiedTable(DbType engine, String schema, String table) {
        return switch (engine) {
            case POSTGRESQL -> (schema != null && !schema.isBlank())
                    ? "\"" + schema + "\".\"" + table + "\""
                    : "\"" + table + "\"";
            case MARIADB -> "`" + table + "`";
        };
    }
}
