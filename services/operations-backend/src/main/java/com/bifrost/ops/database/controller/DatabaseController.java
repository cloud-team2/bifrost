package com.bifrost.ops.database.controller;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.dto.CdcReadinessResponse;
import com.bifrost.ops.database.dto.ConnectionTestRequest;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.database.dto.DatabaseMetricsResponse;
import com.bifrost.ops.database.dto.DatabasePipelineSummary;
import com.bifrost.ops.database.dto.DatabaseRegisterRequest;
import com.bifrost.ops.database.dto.DatabaseResponse;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.service.CdcReadinessService;
import com.bifrost.ops.database.service.DatabaseSchemaService;
import com.bifrost.ops.database.service.DatabaseService;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Database Registry 플랫폼 API(frontend-facing, database-registry.md §6).
 *
 * <p>제공: 연결 테스트(#26), 등록·목록·상세(#27). 스키마(#28)·CDC 준비도(#29)·
 * metrics·pipelines(#30)는 같은 컨트롤러에 추가된다.
 *
 * <p>모든 엔드포인트는 경로의 {@code wsId}가 인증 사용자의 워크스페이스인지 scope 검증한다
 * (v1 단일 멤버십: {@code wsId == principal.tenantId()}, 위반 시 403 RESOURCE_NOT_OWNED_BY_PROJECT).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/databases")
public class DatabaseController {

    private final DatabaseService databaseService;
    private final DatabaseSchemaService schemaService;
    private final CdcReadinessService cdcReadinessService;

    public DatabaseController(DatabaseService databaseService, DatabaseSchemaService schemaService,
                              CdcReadinessService cdcReadinessService) {
        this.databaseService = databaseService;
        this.schemaService = schemaService;
        this.cdcReadinessService = cdcReadinessService;
    }

    /** 연결 테스트(FR-014). 실패도 200으로 분류 반환. */
    @PostMapping("/connection-test")
    public ConnectionTestResponse connectionTest(@PathVariable UUID wsId,
                                                 @AuthenticationPrincipal AuthenticatedUser principal,
                                                 @Valid @RequestBody ConnectionTestRequest req) {
        requireScope(wsId, principal);
        DbType engine = DatabaseService.parseEngine(req.engine());
        return databaseService.testConnection(
                engine, req.host(), req.port(), req.dbName(), req.user(), req.password());
    }

    /** 등록(FR-014). 성공 시 201, 자격증명은 secretRef로 보관·응답 마스킹. */
    @PostMapping
    public ResponseEntity<DatabaseResponse> register(@PathVariable UUID wsId,
                                                     @AuthenticationPrincipal AuthenticatedUser principal,
                                                     @Valid @RequestBody DatabaseRegisterRequest req) {
        requireScope(wsId, principal);
        return ResponseEntity.status(201).body(databaseService.register(wsId, req));
    }

    /** 목록(FR-013). role(파생)·engine·q 필터. */
    @GetMapping
    public List<DatabaseResponse> list(@PathVariable UUID wsId,
                                       @AuthenticationPrincipal AuthenticatedUser principal,
                                       @RequestParam(required = false) String role,
                                       @RequestParam(required = false) String engine,
                                       @RequestParam(required = false) String q) {
        requireScope(wsId, principal);
        return databaseService.list(wsId, role, engine, q);
    }

    /** 상세(password 마스킹). */
    @GetMapping("/{dbId}")
    public DatabaseResponse get(@PathVariable UUID wsId,
                                @PathVariable UUID dbId,
                                @AuthenticationPrincipal AuthenticatedUser principal) {
        requireScope(wsId, principal);
        return databaseService.get(wsId, dbId);
    }

    /** 스키마 조회(FR-016). 테이블·컬럼·타입·nullable·pk·index. */
    @GetMapping("/{dbId}/schema")
    public DatabaseSchemaResponse schema(@PathVariable UUID wsId,
                                         @PathVariable UUID dbId,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        requireScope(wsId, principal);
        return schemaService.getSchema(wsId, dbId);
    }

    /** CDC 준비도 점검(FR-015). {overallStatus, checks[name·status·actual·expected·hint]}. */
    @GetMapping("/{dbId}/cdc-readiness")
    public CdcReadinessResponse cdcReadiness(@PathVariable UUID wsId,
                                             @PathVariable UUID dbId,
                                             @AuthenticationPrincipal AuthenticatedUser principal) {
        requireScope(wsId, principal);
        return cdcReadinessService.check(wsId, dbId);
    }

    /** 지표(FR-017). 이번 주는 계약용 stub 응답. */
    @GetMapping("/{dbId}/metrics")
    public DatabaseMetricsResponse metrics(@PathVariable UUID wsId,
                                           @PathVariable UUID dbId,
                                           @AuthenticationPrincipal AuthenticatedUser principal) {
        requireScope(wsId, principal);
        return databaseService.getMetrics(wsId, dbId);
    }

    /** 이 DB를 쓰는 파이프라인 목록(FR-018). */
    @GetMapping("/{dbId}/pipelines")
    public List<DatabasePipelineSummary> pipelines(@PathVariable UUID wsId,
                                                   @PathVariable UUID dbId,
                                                   @AuthenticationPrincipal AuthenticatedUser principal) {
        requireScope(wsId, principal);
        return databaseService.listPipelines(wsId, dbId);
    }

    /** 경로의 wsId가 인증 사용자 소속 워크스페이스인지 검증(scope). */
    private static void requireScope(UUID wsId, AuthenticatedUser principal) {
        if (principal == null || !wsId.equals(principal.tenantId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_OWNED_BY_PROJECT,
                    "워크스페이스 접근 권한이 없습니다");
        }
    }
}
