package com.bifrost.ops.database.service;

import com.bifrost.ops.database.cdc.CdcReadinessChecker;
import com.bifrost.ops.database.cdc.CdcReadinessCheckerRegistry;
import com.bifrost.ops.database.cdc.CdcReadinessStatus;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CDC 준비도 점검 오케스트레이션(#29, FR-015, database-registry.md §4). 등록 DB를 scope로 찾아
 * 자격증명을 resolve하고 엔진별 checker로 점검한 뒤 결과를 entity에 반영한다.
 *
 * <p>DB 등록 직후 자동 실행과 상세 화면 수동 재점검이 같은 경로를 쓴다. 결과 overallStatus는
 * {@code cdc_readiness_status}, 전체 리포트는 {@code cdc_readiness_report}(jsonb)에 저장한다.
 */
@Service
public class CdcReadinessService {

    private static final Logger log = LoggerFactory.getLogger(CdcReadinessService.class);

    private final DatasourceRepository repo;
    private final SecretStore secretStore;
    private final CdcReadinessCheckerRegistry registry;
    private final DynamicDataSourceFactory dataSourceFactory;
    private final ObjectMapper objectMapper;

    public CdcReadinessService(DatasourceRepository repo, SecretStore secretStore,
                               CdcReadinessCheckerRegistry registry,
                               DynamicDataSourceFactory dataSourceFactory,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.secretStore = secretStore;
        this.registry = registry;
        this.dataSourceFactory = dataSourceFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CdcReadinessResponse check(UUID tenantId, UUID dbId) {
        DatasourceEntity e = repo.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND,
                        "데이터베이스를 찾을 수 없습니다"));
        DbCredential cred = secretStore.resolve(e.getSecretRef());
        CdcReadinessChecker checker = registry.forEngine(e.getDbType());

        List<CdcCheck> checks = runChecks(e, cred.password(), checker);
        CdcReadinessStatus overall =
                CdcReadinessStatus.worst(checks.stream().map(CdcCheck::status).toList());
        CdcReadinessResponse response = new CdcReadinessResponse(overall, checks);

        persist(e, response);
        OpsLog.ok("Database", "CDC 준비도 점검", "db=" + e.getName() + ", overall=" + overall.name());
        return response;
    }

    private List<CdcCheck> runChecks(DatasourceEntity e, String password, CdcReadinessChecker checker) {
        try (HikariDataSource dataSource = dataSourceFactory.create(e, password, true);
             Connection conn = dataSource.getConnection()) {
            return checker.check(conn);
        } catch (SQLException | RuntimeException ex) {
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED, "CDC 준비도 점검 실패");
        }
    }

    private void persist(DatasourceEntity e, CdcReadinessResponse response) {
        e.setCdcReadinessStatus(response.overallStatus().name());
        try {
            e.setCdcReadinessReport(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException ex) {
            log.warn("CDC 리포트 직렬화 실패: dbId={}", e.getId()); // 상태는 저장, 리포트만 생략
        }
        e.setLastInspectedAt(Instant.now());
        repo.save(e);
    }
}
