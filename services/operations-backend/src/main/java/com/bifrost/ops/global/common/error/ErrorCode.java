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
    USER_NOT_FOUND_BY_EMAIL(10006, HttpStatus.NOT_FOUND),

    WORKSPACE_NAME_CONFLICT(20001, HttpStatus.CONFLICT),
    WORKSPACE_NAMESPACE_CONFLICT(20002, HttpStatus.CONFLICT),
    WORKSPACE_NOT_FOUND(20003, HttpStatus.NOT_FOUND),
    WORKSPACE_FORBIDDEN(20004, HttpStatus.FORBIDDEN),
    MEMBER_NOT_FOUND(20005, HttpStatus.NOT_FOUND),
    MEMBER_ALREADY_EXISTS(20006, HttpStatus.CONFLICT),
    OWNER_DEMOTION_FORBIDDEN(20007, HttpStatus.CONFLICT),

    DATABASE_NAME_CONFLICT(30001, HttpStatus.CONFLICT),
    DATABASE_NOT_FOUND(30002, HttpStatus.NOT_FOUND),
    DATABASE_CONNECTION_FAILED(30003, HttpStatus.UNPROCESSABLE_ENTITY),

    PIPELINE_NOT_FOUND(40001, HttpStatus.NOT_FOUND),
    KAFKA_PRINCIPAL_NOT_FOUND(40002, HttpStatus.NOT_FOUND),
    KAFKA_PRINCIPAL_USERNAME_INVALID(40003, HttpStatus.BAD_REQUEST),
    KAFKA_PRINCIPAL_CONFLICT(40004, HttpStatus.CONFLICT),
    KAFKA_PRINCIPAL_ALREADY_REVOKED(40005, HttpStatus.CONFLICT),

    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR),

    VALIDATION_FAILED(90001, HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(90006, HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(90007, HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(90008, HttpStatus.UNSUPPORTED_MEDIA_TYPE);

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
