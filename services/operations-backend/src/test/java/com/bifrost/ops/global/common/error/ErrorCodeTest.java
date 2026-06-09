package com.bifrost.ops.global.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    private static final int METHOD_NOT_ALLOWED_CODE = 90007;
    private static final int UNSUPPORTED_MEDIA_TYPE_CODE = 90008;
    private static final String EMAIL_ALREADY_USED_MESSAGE = "이미 가입된 이메일";

    @Test
    void 각_도메인_번호대가_규칙에_맞는다() {
        for (ErrorCode ec : ErrorCode.values()) {
            int code = ec.code();
            assertThat(code).as(ec.name() + " 번호 범위").isBetween(10000, 99999);
        }
        assertThat(ErrorCode.EMAIL_ALREADY_USED.code() / 10000).isEqualTo(1);
        assertThat(ErrorCode.WORKSPACE_NAME_CONFLICT.code() / 10000).isEqualTo(2);
        assertThat(ErrorCode.INTERNAL_ERROR.code() / 10000).isEqualTo(5);
        assertThat(ErrorCode.VALIDATION_FAILED.code() / 10000).isEqualTo(9);
    }

    @Test
    void 번호는_도메인_안에서_중복되지_않는다() {
        Set<Integer> codes = new HashSet<>();
        for (ErrorCode ec : ErrorCode.values()) {
            assertThat(codes.add(ec.code())).as(ec.name() + " 중복 코드").isTrue();
        }
        assertThat(codes).hasSize(ErrorCode.values().length);
    }

    @Test
    void HTTP_상태가_매핑되어_있다() {
        Arrays.stream(ErrorCode.values()).forEach(ec ->
            assertThat(ec.status()).as(ec.name() + " HTTP status").isNotNull()
        );
        assertThat(ErrorCode.EMAIL_ALREADY_USED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.UNAUTHENTICATED.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.WORKSPACE_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ErrorCode.VALIDATION_FAILED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 신규_공통_4xx_코드_번호가_문서_계약과_일치한다() {
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.code()).isEqualTo(METHOD_NOT_ALLOWED_CODE);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code()).isEqualTo(UNSUPPORTED_MEDIA_TYPE_CODE);
    }

    @Test
    void ErrorResponse_of는_코드를_문자열로_전달한다() {
        ErrorResponse r = ErrorResponse.of(ErrorCode.EMAIL_ALREADY_USED, EMAIL_ALREADY_USED_MESSAGE);
        assertThat(r.code()).isEqualTo(code(ErrorCode.EMAIL_ALREADY_USED));
        assertThat(r.message()).isEqualTo(EMAIL_ALREADY_USED_MESSAGE);
        assertThat(r.details()).isEmpty();
    }

    private static String code(ErrorCode code) {
        return String.valueOf(code.code());
    }
}
