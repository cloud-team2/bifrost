package com.bifrost.ops.auth.controller;

import com.bifrost.ops.auth.AuthPaths;
import com.bifrost.ops.auth.dto.AuthTokensResponse;
import com.bifrost.ops.auth.dto.LoginRequest;
import com.bifrost.ops.auth.dto.RegisterRequest;
import com.bifrost.ops.auth.jwt.JwtAuthenticationFilter;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.auth.security.JwtAuthenticationEntryPoint;
import com.bifrost.ops.auth.security.SecurityConfig;
import com.bifrost.ops.auth.service.AuthService;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.error.ErrorMessages;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private static final String JSON_CODE = "$.code";
    private static final String JSON_MESSAGE = "$.message";
    private static final String TOKEN_VALUE = "token";
    private static final String TOKEN_TYPE = "Bearer";
    private static final String VALID_TOKEN = "valid-token";
    private static final String VALID_EMAIL = "user@bifrost.io";
    private static final String LEGACY_ADMIN_ALIAS = AuthPaths.LEGACY_AUTH_BASE + "/admin";
    private static final String LEGACY_REGISTER_ALIAS = AuthPaths.LEGACY_AUTH_BASE + AuthPaths.REGISTER;
    private static final String EMPTY_JSON = "{}";
    private static final String VALID_REGISTER_JSON = """
        {"email":"user@test.com","password":"password123","workspaceName":"Team A","namespace":"team-a"}
        """;
    private static final String VALID_LOGIN_JSON = """
        {"email":"user@test.com","password":"password123"}
        """;
    private static final long TOKEN_TTL_SECONDS = 3600L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void canonicalRegisterIsPublicAndDelegates() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(tokens());

        mockMvc.perform(post(AuthPaths.API_V1_REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REGISTER_JSON))
            .andExpect(status().isCreated());
    }

    @Test
    void canonicalLoginIsPublicAndDelegates() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(tokens());

        mockMvc.perform(post(AuthPaths.API_V1_LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_LOGIN_JSON))
            .andExpect(status().isOk());
    }

    @Test
    void canonicalRefreshRequiresBearerAtMatcher() throws Exception {
        mockMvc.perform(post(AuthPaths.API_V1_REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_JSON))
            .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNAUTHENTICATED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.AUTHENTICATION_REQUIRED));
    }

    @Test
    void canonicalMeRequiresBearerAtMatcher() throws Exception {
        mockMvc.perform(get(AuthPaths.API_V1_ME))
            .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNAUTHENTICATED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.AUTHENTICATION_REQUIRED));
    }

    @Test
    void legacyProtectedAuthMeRequiresBearerAtMatcher() throws Exception {
        mockMvc.perform(get(AuthPaths.LEGACY_ME))
            .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNAUTHENTICATED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.AUTHENTICATION_REQUIRED));
    }

    @Test
    void legacyProtectedAuthMeReturns404AfterValidBearer() throws Exception {
        givenValidBearer();

        mockMvc.perform(get(AuthPaths.LEGACY_ME)
                .header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().is(ErrorCode.RESOURCE_NOT_FOUND.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.RESOURCE_NOT_FOUND)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.RESOURCE_NOT_FOUND));
    }

    @Test
    void legacyProtectedRefreshRequiresBearerAtMatcher() throws Exception {
        mockMvc.perform(post(AuthPaths.LEGACY_REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_JSON))
            .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNAUTHENTICATED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.AUTHENTICATION_REQUIRED));
    }

    @Test
    void legacyPublicLoginAliasReachesMvc404WithoutBearer() throws Exception {
        mockMvc.perform(post(AuthPaths.LEGACY_LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_JSON))
            .andExpect(status().is(ErrorCode.RESOURCE_NOT_FOUND.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.RESOURCE_NOT_FOUND)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.RESOURCE_NOT_FOUND));
    }

    @Test
    void legacyRegisterAliasNoLongerUsesBroadPermitAll() throws Exception {
        mockMvc.perform(post(LEGACY_REGISTER_ALIAS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REGISTER_JSON))
            .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNAUTHENTICATED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.AUTHENTICATION_REQUIRED));
    }

    @Test
    void legacyAuthPrefixNoLongerPermitsUnknownChildren() throws Exception {
        mockMvc.perform(get(LEGACY_ADMIN_ALIAS))
            .andExpect(status().is(ErrorCode.UNAUTHENTICATED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNAUTHENTICATED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.AUTHENTICATION_REQUIRED));
    }

    private void givenValidBearer() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(UUID.randomUUID().toString());
        when(claims.get("tid", String.class)).thenReturn(UUID.randomUUID().toString());
        when(claims.get("email", String.class)).thenReturn(VALID_EMAIL);
        when(jwtService.parse(VALID_TOKEN)).thenReturn(claims);
    }

    private static AuthTokensResponse tokens() {
        return new AuthTokensResponse(
            TOKEN_VALUE,
            TOKEN_TYPE,
            TOKEN_TTL_SECONDS,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
    }

    private static String bearer() {
        return TOKEN_TYPE + " " + VALID_TOKEN;
    }

    private static String code(ErrorCode code) {
        return String.valueOf(code.code());
    }
}
