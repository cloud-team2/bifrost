package com.bifrost.ops.api.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

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
    }

    @Test
    void ErrorResponse_of는_숫자_코드를_그대로_전달한다() {
        ErrorResponse r = ErrorResponse.of(ErrorCode.EMAIL_ALREADY_USED, "이미 가입된 이메일");
        assertThat(r.code()).isEqualTo(10001);
        assertThat(r.message()).isEqualTo("이미 가입된 이메일");
        assertThat(r.details()).isEmpty();
    }
}
