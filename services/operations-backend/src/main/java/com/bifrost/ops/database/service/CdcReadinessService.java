package com.bifrost.ops.database.service;

import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.inspector.DatabaseInspectorFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * CDC 준비도 점검 오케스트레이션(#29, FR-015). Source/Sink 두 경로를 모두 제공한다.
 *
 * <p>자격증명 resolve → {@link DatabaseInspector}에 위임 → 결과를 entity에 영속.
 */
@Service
public class CdcReadinessService {

    private static final Logger log = LoggerFactory.getLogger(CdcReadinessService.class);

    private final DatasourceRepository repo;
    private final SecretStore secretStore;
    private final DatabaseInspectorFactory inspectorFactory;
    private final ObjectMapper objectMapper;

    public CdcReadinessService(DatasourceRepository repo, SecretStore secretStore,
                               DatabaseInspectorFactory inspectorFactory,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.secretStore = secretStore;
        this.inspectorFactory = inspectorFactory;
        this.objectMapper = objectMapper;
    }

    /** CDC Source 준비도 점검 및 결과 영속. */
    @Transactional
    public CdcReadinessResponse check(UUID tenantId, UUID dbId) {
        DatasourceEntity e = findEntity(tenantId, dbId);
        DbCredential cred = secretStore.resolve(e.getSecretRef());
        CdcReadinessResponse response;
        try (DatabaseInspector inspector = inspectorFactory.create(e, cred.password())) {
            response = inspector.checkSourceReadiness();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED, "CDC 준비도 점검 실패");
        }
        persistSource(e, response);
        OpsLog.ok("Database", "CDC source 준비도 점검",
                "db=" + e.getName() + ", overall=" + response.overallStatus().name());
        return response;
    }

    /** CDC Sink 준비도 점검 및 결과 영속. */
    @Transactional
    public CdcReadinessResponse checkSink(UUID tenantId, UUID dbId) {
        DatasourceEntity e = findEntity(tenantId, dbId);
        DbCredential cred = secretStore.resolve(e.getSecretRef());
        CdcReadinessResponse response;
        try (DatabaseInspector inspector = inspectorFactory.create(e, cred.password())) {
            response = inspector.checkSinkReadiness();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED, "Sink 준비도 점검 실패");
        }
        persistSink(e, response);
        OpsLog.ok("Database", "CDC sink 준비도 점검",
                "db=" + e.getName() + ", overall=" + response.overallStatus().name());
        return response;
    }

    private DatasourceEntity findEntity(UUID tenantId, UUID dbId) {
        return repo.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND,
                        "데이터베이스를 찾을 수 없습니다"));
    }

    private void persistSource(DatasourceEntity e, CdcReadinessResponse response) {
        e.setCdcReadinessStatus(response.overallStatus().name());
        try {
            e.setCdcReadinessReport(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException ex) {
            log.warn("CDC source 리포트 직렬화 실패: dbId={}", e.getId());
        }
        e.setLastInspectedAt(Instant.now());
        repo.save(e);
    }

    private void persistSink(DatasourceEntity e, CdcReadinessResponse response) {
        e.setSinkReadinessStatus(response.overallStatus().name());
        try {
            e.setSinkReadinessReport(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException ex) {
            log.warn("CDC sink 리포트 직렬화 실패: dbId={}", e.getId());
        }
        e.setLastInspectedAt(Instant.now());
        repo.save(e);
    }
}
