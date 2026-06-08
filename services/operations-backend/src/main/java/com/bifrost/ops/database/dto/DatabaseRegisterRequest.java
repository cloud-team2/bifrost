package com.bifrost.ops.database.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Database 등록 요청(#27, FR-014, database-registry.md §3).
 *
 * <p>등록 전 연결을 검증(§3 Step 1)하고, 성공 시 자격증명을 SecretStore에 보관(§3 Step 2)한다.
 * 자격증명(password)은 응답·로그에 남기지 않는다.
 *
 * @param name     표시 이름(alias). 워크스페이스 내 유일(중복 시 30001).
 * @param engine   DB 엔진(예: {@code postgresql}, {@code mariadb}). 대소문자 무시.
 * @param password 빈 문자열 허용, null 불가.
 */
public record DatabaseRegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String engine,
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotBlank String dbName,
        @NotBlank String username,
        @NotNull String password
) {
}
