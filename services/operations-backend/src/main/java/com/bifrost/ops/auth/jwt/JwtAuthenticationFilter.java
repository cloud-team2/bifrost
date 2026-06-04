package com.bifrost.ops.auth.jwt;

import com.bifrost.ops.global.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parse(token);
            UUID userId = UUID.fromString(claims.getSubject());
            UUID tenantId = UUID.fromString(claims.get("tid", String.class));
            String email = claims.get("email", String.class);

            AuthenticatedUser principal = new AuthenticatedUser(userId, tenantId, email);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, AuthorityUtils.NO_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute("ops.errorCode", ErrorCode.AUTH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute("ops.errorCode", ErrorCode.AUTH_TOKEN_INVALID);
        }

        chain.doFilter(request, response);
    }

    /**
     * 토큰 추출. 우선 {@code Authorization: Bearer} 헤더, 없으면 SSE 스트림 경로에 한해
     * {@code ?access_token=} 쿼리 파라미터를 읽는다(브라우저 EventSource는 헤더를 못 붙임).
     */
    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length());
        }
        if (request.getRequestURI().endsWith("/events/stream")) {
            String param = request.getParameter("access_token");
            if (param != null && !param.isBlank()) {
                return param;
            }
        }
        return null;
    }
}
