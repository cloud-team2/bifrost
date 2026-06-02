package com.bifrost.ops.api.error;

import java.util.List;

public record ErrorResponse(String code, String message, List<FieldError> details) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(String.valueOf(code.code()), message, List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> details) {
        return new ErrorResponse(String.valueOf(code.code()), message, details);
    }

    public record FieldError(String field, String reason) {}
}
