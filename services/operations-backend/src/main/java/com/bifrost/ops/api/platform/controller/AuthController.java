package com.bifrost.ops.api.platform.controller;

import com.bifrost.ops.api.error.ApiException;
import com.bifrost.ops.api.error.ErrorCode;
import com.bifrost.ops.api.platform.dto.AuthTokensResponse;
import com.bifrost.ops.api.platform.dto.LoginRequest;
import com.bifrost.ops.api.platform.dto.MeResponse;
import com.bifrost.ops.api.platform.dto.RegisterRequest;
import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthTokensResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(authService.register(req));
    }

    @PostMapping("/login")
    public AuthTokensResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        return authService.me(principal);
    }
}
