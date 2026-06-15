package com.bifrost.ops.database.service;

import com.bifrost.ops.database.connection.DatabaseConnectionTester;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.database.dto.DatabaseMetricsResponse;
import com.bifrost.ops.database.dto.DatabasePipelineSummary;
import com.bifrost.ops.database.dto.DatabaseRegisterRequest;
import com.bifrost.ops.database.dto.DatabaseResponse;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.inspector.DatabaseInspectorFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretContext;
import com.bifrost.ops.secret.SecretStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Database 등록·목록·상세(#27, FR-013·FR-014, database-registry.md §3).
 *
 * <p>등록은 §3의 3단계 중 1~2를 수행한다: 연결 검증(Step 1) → 성공 시 자격증명을 SecretStore에
 * 보관하고 메타DB엔 {@code secret_ref}만 저장(Step 2). CDC 준비도(Step 3)는 #29.
 *
 * <p>자격증명은 SecretStore 밖으로 나가지 않는다 — 응답은 {@code ****}, 로그에도 남기지 않는다.
 */
@Service
public class DatabaseService {

    private final DatasourceRepository repo;
    private final SecretStore secretStore;
    private final DatabaseConnectionTester connectionTester;
    private final DatabaseInspectorFactory inspectorFactory;

    public DatabaseService(DatasourceRepository repo, SecretStore secretStore,
                           DatabaseConnectionTester connectionTester,
                           DatabaseInspectorFactory inspectorFactory) {
        this.repo = repo;
        this.secretStore = secretStore;
        this.connectionTester = connectionTester;
        this.inspectorFactory = inspectorFactory;
    }

    /** 연결 테스트(FR-014). 실패도 200 본문으로 분류 반환. */
    public ConnectionTestResponse testConnection(DbType engine, String host, int port,
                                                 String dbName, String user, String password) {
        ConnectionTestResponse resp = connectionTester.test(engine, host, port, dbName, user, password);
        if (resp.success()) {
            OpsLog.ok("Database", "연결 테스트 성공",
                    "engine=" + engine.name().toLowerCase() + ", host=" + host + ":" + port);
        } else {
            OpsLog.fail("Database", "연결 테스트 실패",
                    "host=" + host + ":" + port + ", reason=" + resp.reason());
        }
        return resp;
    }

    /** 등록(FR-014). 이름 중복·연결 실패 시 예외, 성공 시 secretRef 보관 후 마스킹 응답. */
    @Transactional
    public DatabaseResponse register(UUID tenantId, DatabaseRegisterRequest req) {
        if (repo.existsByTenantIdAndName(tenantId, req.name())) {
            OpsLog.fail("Database", "DB 등록 실패", "name=" + req.name() + ", reason=이미 사용 중인 이름");
            throw new ApiException(ErrorCode.DATABASE_NAME_CONFLICT,
                    "이미 사용 중인 이름입니다: " + req.name());
        }
        DbType engine = parseEngine(req.engine());

        // §3 Step 1 — 연결 검증. 실패하면 저장하지 않는다(분류 사유를 메시지로 전달).
        ConnectionTestResponse test = connectionTester.test(
                engine, req.host(), req.port(), req.dbName(), req.username(), req.password());
        if (!test.success()) {
            OpsLog.fail("Database", "DB 등록 실패",
                    "name=" + req.name() + ", reason=연결 검증 실패(" + test.reason() + ")");
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED,
                    "연결 검증 실패: " + test.reason());
        }

        // §3 Step 2 — 자격증명은 SecretStore에, 메타DB엔 secret_ref만.
        String secretRef = secretStore.put(
                new SecretContext(tenantId, req.name()),
                new DbCredential(req.username(), req.password()));

        DatasourceEntity e = new DatasourceEntity();
        e.setTenantId(tenantId);
        e.setName(req.name());
        e.setDbType(engine);
        e.setHost(req.host());
        e.setPort(req.port());
        e.setDbName(req.dbName());
        e.setUsername(req.username());
        e.setSecretRef(secretRef);
        repo.save(e);

        OpsLog.ok("Database", "DB 등록",
                "name=" + e.getName() + ", engine=" + engine.name().toLowerCase()
                        + ", host=" + e.getHost() + ":" + e.getPort());
        // 신규 등록 — 아직 파이프라인에서 쓰이지 않으므로 파생 역할 없음.
        return DatabaseResponse.of(e, List.of());
    }

    /** 목록(FR-013). engine·q(name 부분일치)·role(파생) 필터. */
    @Transactional(readOnly = true)
    public List<DatabaseResponse> list(UUID tenantId, String role, String engine, String q) {
        Set<UUID> sourceIds = new HashSet<>(repo.findSourceDatasourceIds(tenantId));
        Set<UUID> sinkIds = new HashSet<>(repo.findSinkDatasourceIds(tenantId));
        DbType engineFilter = (engine == null || engine.isBlank()) ? null : parseEngine(engine);
        String qLower = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        String roleFilter = (role == null || role.isBlank()) ? null : role.trim().toLowerCase();

        return repo.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(e -> engineFilter == null || e.getDbType() == engineFilter)
                .filter(e -> qLower == null || e.getName().toLowerCase().contains(qLower))
                .map(e -> DatabaseResponse.of(e, rolesOf(e, sourceIds, sinkIds)))
                .filter(r -> roleFilter == null || r.roles().contains(roleFilter))
                .toList();
    }

    /** 상세(password 마스킹). 없거나 타 워크스페이스면 404. */
    @Transactional(readOnly = true)
    public DatabaseResponse get(UUID tenantId, UUID dbId) {
        DatasourceEntity e = repo.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND,
                        "데이터베이스를 찾을 수 없습니다"));
        Set<UUID> sourceIds = new HashSet<>(repo.findSourceDatasourceIds(tenantId));
        Set<UUID> sinkIds = new HashSet<>(repo.findSinkDatasourceIds(tenantId));
        return DatabaseResponse.of(e, rolesOf(e, sourceIds, sinkIds));
    }

    /** 지표(FR-017). 등록 DB에 직접 연결해 엔진 stat과 probe query로 산출한다. */
    @Transactional(readOnly = true)
    public DatabaseMetricsResponse getMetrics(UUID tenantId, UUID dbId) {
        DatasourceEntity e = repo.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND,
                        "데이터베이스를 찾을 수 없습니다"));
        DbCredential cred = secretStore.resolve(e.getSecretRef());
        try (DatabaseInspector inspector = inspectorFactory.create(e, cred.password())) {
            DatabaseInspector.MetricsSnapshot metrics = inspector.collectMetrics();
            return DatabaseMetricsResponse.live(metrics.tps(), metrics.queryResponseMs(),
                    metrics.queryResponseP95Ms(), metrics.activeConnections());
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED, "DB metrics 조회 실패");
        }
    }

    /** DB 삭제. 파이프라인에서 사용 중이면 거부. */
    @Transactional
    public void delete(UUID tenantId, UUID dbId) {
        DatasourceEntity e = repo.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND,
                        "데이터베이스를 찾을 수 없습니다"));
        boolean usedAsSource = repo.findSourceDatasourceIds(tenantId).contains(dbId);
        boolean usedAsSink = repo.findSinkDatasourceIds(tenantId).contains(dbId);
        if (usedAsSource || usedAsSink) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "이 DB를 사용하는 파이프라인이 있습니다. 파이프라인을 먼저 삭제하세요.");
        }
        secretStore.delete(e.getSecretRef());
        repo.delete(e);
        OpsLog.ok("Database", "DB 삭제", "name=" + e.getName());
    }

    /** 워크스페이스 삭제 전 DB/secret 정리. 파이프라인에서 사용 중이면 기존 DB 삭제 정책과 같이 거부한다. */
    @Transactional
    public void deleteAllForWorkspace(UUID tenantId) {
        if (!repo.findSourceDatasourceIds(tenantId).isEmpty() || !repo.findSinkDatasourceIds(tenantId).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "이 워크스페이스의 DB를 사용하는 파이프라인이 있습니다. 파이프라인을 먼저 삭제하세요.");
        }
        for (DatasourceEntity e : repo.findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            secretStore.delete(e.getSecretRef());
            repo.delete(e);
            OpsLog.ok("Database", "DB 삭제", "name=" + e.getName());
        }
        repo.flush();
    }

    /** 이 DB를 source로 쓰는 파이프라인 목록(FR-018). */
    @Transactional(readOnly = true)
    public List<DatabasePipelineSummary> listPipelines(UUID tenantId, UUID dbId) {
        requireExists(tenantId, dbId);
        return repo.findPipelinesUsingDatasource(tenantId, dbId).stream()
                .map(r -> new DatabasePipelineSummary(
                        r.getId().toString(), r.getName(), r.getType(), r.getStatus()))
                .toList();
    }

    private void requireExists(UUID tenantId, UUID dbId) {
        if (repo.findByIdAndTenantId(dbId, tenantId).isEmpty()) {
            throw new ApiException(ErrorCode.DATABASE_NOT_FOUND, "데이터베이스를 찾을 수 없습니다");
        }
    }

    private static List<String> rolesOf(DatasourceEntity e, Set<UUID> sourceIds, Set<UUID> sinkIds) {
        List<String> roles = new java.util.ArrayList<>();
        if (sourceIds.contains(e.getId())) roles.add("source");
        if (sinkIds.contains(e.getId())) roles.add("sink");
        return roles;
    }

    /** engine 문자열 → DbType(대소문자 무시). 미지원이면 VALIDATION_FAILED. */
    public static DbType parseEngine(String engine) {
        try {
            return DbType.valueOf(engine.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 engine: " + engine);
        }
    }
}
