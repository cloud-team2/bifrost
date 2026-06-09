package com.bifrost.ops.auth.controller;

import com.bifrost.ops.auth.dto.AuthTokensResponse;
import com.bifrost.ops.auth.dto.LoginRequest;
import com.bifrost.ops.auth.dto.RegisterRequest;
import com.bifrost.ops.auth.jwt.JwtAuthenticationFilter;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.auth.security.JwtAuthenticationEntryPoint;
import com.bifrost.ops.auth.security.SecurityConfig;
import com.bifrost.ops.auth.service.AuthService;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void registerAndLoginOnlyUnderApiV1Auth() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(tokens());
        when(authService.login(any(LoginRequest.class))).thenReturn(tokens());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"user@test.com","password":"password123","workspaceName":"Team A","namespace":"team-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"user@test.com","password":"password123"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"user@test.com","password":"password123","workspaceName":"Team A","namespace":"team-a"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"user@test.com","password":"password123"}
                                """))
                .andExpect(status().isNotFound());
    }

    private static AuthTokensResponse tokens() {
        return new AuthTokensResponse("token", "Bearer", 3600, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void legacyAliasAuthMeReturns404WithoutBearer() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("90006"))
            .andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없습니다"));
    }

    @Test
    void legacyAliasAuthMeReturns404WithBearer() throws Exception {
        when(jwtService.parse("legacy-token")).thenThrow(new JwtException("invalid"));

        mockMvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer legacy-token"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("90006"))
            .andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없습니다"));
    }
}
