package com.bifrost.ops.auth.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.auth.dto.AuthTokensResponse;
import com.bifrost.ops.auth.dto.LoginRequest;
import com.bifrost.ops.auth.dto.MeResponse;
import com.bifrost.ops.auth.dto.RegisterRequest;
import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API. 프론트 기본 호출은 {@code /api/v1/auth/**} 계약(#72)을 따르며,
 * 기존 {@code /api/auth/**} 경로도 하위 호환으로 유지한다.
 */
@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
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

    /**
     * 액세스 토큰 재발급(최소 stub). 유효한 토큰으로 인증된 사용자에게 새 토큰을 발급한다.
     * httpOnly refresh cookie 등 완성형 흐름은 후속 작업.
     */
    @PostMapping("/refresh")
    public AuthTokensResponse refresh(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        return authService.refresh(principal);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        return authService.me(principal);
    }
}
