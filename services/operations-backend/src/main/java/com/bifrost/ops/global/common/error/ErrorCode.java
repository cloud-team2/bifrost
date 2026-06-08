package com.bifrost.ops.global.common.error;

import org.springframework.http.HttpStatus;

/**
 * 코드 번호 규칙은 docs/api/error-codes.md 참조.
 * 한 번 부여된 번호는 재사용 금지(아카이브).
 */
public enum ErrorCode {

    EMAIL_ALREADY_USED(10001, HttpStatus.CONFLICT),
    INVALID_CREDENTIALS(10002, HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(10003, HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_INVALID(10004, HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED(10005, HttpStatus.UNAUTHORIZED),

    WORKSPACE_NAME_CONFLICT(20001, HttpStatus.CONFLICT),
    WORKSPACE_NAMESPACE_CONFLICT(20002, HttpStatus.CONFLICT),
    WORKSPACE_NOT_FOUND(20003, HttpStatus.NOT_FOUND),
    RESOURCE_NOT_OWNED_BY_PROJECT(20004, HttpStatus.FORBIDDEN),

    DATABASE_NAME_CONFLICT(30001, HttpStatus.CONFLICT),
    DATABASE_NOT_FOUND(30002, HttpStatus.NOT_FOUND),
    DATABASE_CONNECTION_FAILED(30003, HttpStatus.UNPROCESSABLE_ENTITY),

    PIPELINE_NOT_FOUND(40001, HttpStatus.NOT_FOUND),

    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR),

    VALIDATION_FAILED(90001, HttpStatus.BAD_REQUEST);

    private final int code;
    private final HttpStatus status;

    ErrorCode(int code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
