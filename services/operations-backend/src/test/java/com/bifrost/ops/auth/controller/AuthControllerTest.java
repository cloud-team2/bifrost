package com.bifrost.ops.auth.controller;

import com.bifrost.ops.auth.jwt.JwtAuthenticationFilter;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.auth.security.JwtAuthenticationEntryPoint;
import com.bifrost.ops.auth.security.SecurityConfig;
import com.bifrost.ops.auth.service.AuthService;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({
    SecurityConfig.class,
    JwtAuthenticationEntryPoint.class,
    JwtAuthenticationFilter.class,
    GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
    "spring.mvc.throw-exception-if-no-handler-found=true",
    "spring.web.resources.add-mappings=false"
})
class AuthControllerTest {

    private static final String LEGACY_AUTH_ME_PATH = "/api/auth/me";
    private static final String LEGACY_TOKEN = "legacy-token";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String INVALID_JWT_MESSAGE = "invalid";
    private static final String RESOURCE_NOT_FOUND_MESSAGE = "요청한 경로를 찾을 수 없습니다";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void legacyAliasAuthMeReturns404WithoutBearer() throws Exception {
        assertResourceNotFoundEnvelope(mockMvc.perform(get(LEGACY_AUTH_ME_PATH)));
    }

    @Test
    void legacyAliasAuthMeReturns404WithBearer() throws Exception {
        when(jwtService.parse(LEGACY_TOKEN)).thenThrow(new JwtException(INVALID_JWT_MESSAGE));

        assertResourceNotFoundEnvelope(mockMvc.perform(get(LEGACY_AUTH_ME_PATH)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + LEGACY_TOKEN)));
    }

    private static ResultActions assertResourceNotFoundEnvelope(ResultActions actions) throws Exception {
        return actions
            .andExpect(status().is(HttpStatus.NOT_FOUND.value()))
            .andExpect(jsonPath("$.code").value(String.valueOf(ErrorCode.RESOURCE_NOT_FOUND.code())))
            .andExpect(jsonPath("$.message").value(RESOURCE_NOT_FOUND_MESSAGE))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details").isEmpty());
    }
}
