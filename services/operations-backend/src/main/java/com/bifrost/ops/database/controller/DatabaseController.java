package com.bifrost.ops.database.controller;

import com.bifrost.ops.database.connection.DatabaseConnectionTester;
import com.bifrost.ops.database.dto.ConnectionTestRequest;
import com.bifrost.ops.database.dto.ConnectionTestResponse;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Database Registry 플랫폼 API(frontend-facing, database-registry.md §6).
 *
 * <p>이번 슬라이스(#26)는 연결 테스트만 제공한다. 등록·목록·상세는 #27, 스키마는 #28,
 * CDC 준비도는 #29, metrics·pipelines는 #30에서 같은 컨트롤러에 추가된다.
 *
 * <p>{@code wsId} 워크스페이스 scope·ownership 검증은 #27에서 도입한다(연결 테스트는
 * 영속 상태를 건드리지 않는다).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wsId}/databases")
public class DatabaseController {

    private final DatabaseConnectionTester connectionTester;

    public DatabaseController(DatabaseConnectionTester connectionTester) {
        this.connectionTester = connectionTester;
    }

    /** 연결 테스트(FR-014). 실패도 200으로 분류해 반환한다. */
    @PostMapping("/connection-test")
    public ConnectionTestResponse connectionTest(@PathVariable UUID wsId,
                                                 @Valid @RequestBody ConnectionTestRequest req) {
        DbType engine = parseEngine(req.engine());
        return connectionTester.test(
                engine, req.host(), req.port(), req.dbName(), req.user(), req.password());
    }

    private static DbType parseEngine(String engine) {
        try {
            return DbType.valueOf(engine.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 engine: " + engine);
        }
    }
}
