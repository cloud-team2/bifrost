package com.bifrost.ops.auth.controller;

import com.bifrost.ops.auth.AuthPaths;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.error.ErrorMessages;
import com.bifrost.ops.auth.dto.AuthTokensResponse;
import com.bifrost.ops.auth.dto.LoginRequest;
import com.bifrost.ops.auth.dto.MeResponse;
import com.bifrost.ops.auth.dto.RegisterRequest;
import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API. 프론트 기본 호출은 {@code /api/v1/auth/**} 계약(#72)을 따른다. */
@Tag(name = "Auth", description = "회원가입, 로그인, 토큰 갱신, 내 계정 조회 API")
@RestController
@RequestMapping(AuthPaths.API_V1_AUTH_BASE)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "회원가입", description = "사용자와 최초 워크스페이스를 생성하고 access token을 발급한다.")
    @PostMapping(AuthPaths.REGISTER)
    public ResponseEntity<AuthTokensResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호를 검증하고 access token을 발급한다.")
    @PostMapping(AuthPaths.LOGIN)
    public AuthTokensResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /**
     * 액세스 토큰 재발급(최소 stub). 유효한 토큰으로 인증된 사용자에게 새 토큰을 발급한다.
     * httpOnly refresh cookie 등 완성형 흐름은 후속 작업.
     */
    @Operation(summary = "토큰 갱신", description = "유효한 Bearer token의 주체에게 새 access token을 발급한다.")
    @PostMapping(AuthPaths.REFRESH)
    public AuthTokensResponse refresh(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, ErrorMessages.AUTHENTICATION_REQUIRED);
        }
        return authService.refresh(principal);
    }

    @Operation(summary = "내 계정 조회", description = "현재 인증 사용자의 이름, 이메일, 역할, 가입/최근 로그인 정보와 워크스페이스 정보를 반환한다.")
    @GetMapping(AuthPaths.ME)
    public MeResponse me(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, ErrorMessages.AUTHENTICATION_REQUIRED);
        }
        return authService.me(principal);
    }
}
