package com.bifrost.ops.auth.controller;

import com.bifrost.ops.auth.dto.AuthTokensResponse;
import com.bifrost.ops.auth.dto.LoginRequest;
import com.bifrost.ops.auth.dto.RegisterRequest;
import com.bifrost.ops.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(authService))
            .build();

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
}
