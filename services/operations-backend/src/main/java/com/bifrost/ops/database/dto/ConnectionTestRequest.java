package com.bifrost.ops.database.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 연결 테스트 요청(database-registry.md §2·§6). 아직 등록 전 raw 입력이다.
 *
 * @param engine   DB 엔진(예: {@code postgresql}, {@code mariadb}). 대소문자 무시.
 * @param password 빈 문자열 허용(빈 비밀번호 DB), null 불가.
 */
public record ConnectionTestRequest(
        @NotBlank String engine,
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotBlank String dbName,
        @NotBlank String user,
        @NotNull String password
) {
}
