package com.bifrost.ops.internalops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * FastAPI agent가 기대하는 공통 응답 봉투.
 *
 * <p>{ ok, request_id, operation, result, evidence[], audit_event_id }
 * 모든 /internal/ops/* 엔드포인트는 이 타입으로 감싸 반환한다. 에러는 {@link OpsError}로 표현한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpsEnvelope<T>(
        boolean ok,
        @JsonProperty("request_id")
        String requestId,
        String operation,
        T result,
        List<String> evidence,
        @JsonProperty("audit_event_id")
        String auditEventId,
        OpsError error
) {

    public static <T> OpsEnvelope<T> ok(String requestId, String operation, T result) {
        return new OpsEnvelope<>(true, requestId, operation, result, List.of(), null, null);
    }

    public static <T> OpsEnvelope<T> error(String requestId, String operation, String message) {
        return error(requestId, operation, "INTERNAL_ERROR", message, false, null);
    }

    public static <T> OpsEnvelope<T> error(String requestId, String operation, String code, String message) {
        return error(requestId, operation, code, message, false, null);
    }

    public static <T> OpsEnvelope<T> error(String requestId,
                                           String operation,
                                           String code,
                                           String message,
                                           boolean retryable) {
        return error(requestId, operation, code, message, retryable, null);
    }

    public static <T> OpsEnvelope<T> error(String requestId,
                                           String operation,
                                           String code,
                                           String message,
                                           boolean retryable,
                                           String requiredAction) {
        return new OpsEnvelope<>(false, requestId, operation, null, List.of(), null,
                new OpsError(code, message, retryable, requiredAction));
    }
}
