package com.bifrost.ops.api.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT),
    WORKSPACE_NAME_CONFLICT(HttpStatus.CONFLICT),
    WORKSPACE_NAMESPACE_CONFLICT(HttpStatus.CONFLICT),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
