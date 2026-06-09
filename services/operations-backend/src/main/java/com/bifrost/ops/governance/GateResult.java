package com.bifrost.ops.governance;

/** 거버넌스 게이트 실행 결과(S3). */
public record GateResult<T>(
        GateStatus status,
        T value,
        String message
) {
    public static <T> GateResult<T> ok(T value) {
        return new GateResult<>(GateStatus.OK, value, null);
    }

    public static <T> GateResult<T> requireApproval(String message) {
        return new GateResult<>(GateStatus.REQUIRE_APPROVAL, null, message);
    }

    public static <T> GateResult<T> blocked(String message) {
        return new GateResult<>(GateStatus.BLOCKED, null, message);
    }

    public static <T> GateResult<T> duplicate(T cachedValue) {
        return new GateResult<>(GateStatus.DUPLICATE, cachedValue, "idempotent duplicate");
    }

    public enum GateStatus { OK, REQUIRE_APPROVAL, BLOCKED, DUPLICATE }
}
