package com.bifrost.ops.auth.security;

import com.bifrost.ops.global.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void rejectsLegacyApiAuthAlias() throws IOException {
        JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, null);

        assertThat(response.getStatus()).isEqualTo(ErrorCode.UNAUTHENTICATED.status().value());
        assertThat(response.getContentAsString()).contains("\"code\":\"10003\"");
    }

    // (#646) /internal/ops service-identity 게이트 판정
    @Test
    void internalOpsGateDisabledWhenTokenUnset() {
        assertThat(SecurityConfig.internalOpsAllowed("", "anything")).isTrue();
        assertThat(SecurityConfig.internalOpsAllowed("", null)).isTrue();
        assertThat(SecurityConfig.internalOpsAllowed(null, null)).isTrue();
    }

    @Test
    void internalOpsGateAllowsMatchingTokenOnly() {
        assertThat(SecurityConfig.internalOpsAllowed("s3cret", "s3cret")).isTrue();
        assertThat(SecurityConfig.internalOpsAllowed("s3cret", "wrong")).isFalse();
        assertThat(SecurityConfig.internalOpsAllowed("s3cret", null)).isFalse();
    }
}
