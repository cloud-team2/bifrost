package com.bifrost.ops.global.common.error;

public final class ErrorMessages {

    public static final String AUTHENTICATION_REQUIRED = "인증이 필요합니다";
    public static final String VALIDATION_FAILED = "유효성 검증 실패";
    public static final String RESOURCE_NOT_FOUND = "요청한 경로를 찾을 수 없습니다";
    public static final String METHOD_NOT_ALLOWED = "지원하지 않는 HTTP 메서드입니다";
    public static final String UNSUPPORTED_MEDIA_TYPE = "지원하지 않는 Content-Type입니다";
    public static final String INTERNAL_ERROR = "내부 오류";

    private ErrorMessages() {
    }
}
