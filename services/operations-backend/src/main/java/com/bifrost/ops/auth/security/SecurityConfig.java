package com.bifrost.ops.auth.security;

import com.bifrost.ops.auth.jwt.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtAuthenticationEntryPoint entryPoint;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, JwtAuthenticationEntryPoint entryPoint) {
        this.jwtFilter = jwtFilter;
        this.entryPoint = entryPoint;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** /internal/ops service-to-service 인증 헤더(#646). ai-service spring_client가 짝으로 동봉한다. */
    static final String INTERNAL_OPS_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Value("${internal.ops.token:}") String internalOpsToken) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, SecurityPaths.PUBLIC_AUTH_POST_PATHS).permitAll()
                .requestMatchers(HttpMethod.POST, SecurityPaths.PROTECTED_AUTH_POST_PATHS).authenticated()
                .requestMatchers(HttpMethod.GET, SecurityPaths.PROTECTED_AUTH_GET_PATHS).authenticated()
                .requestMatchers(SecurityPaths.ACTUATOR_PATHS).permitAll()
                .requestMatchers(SecurityPaths.OPEN_API_PATHS).permitAll()
                // (#646) 내부 전용 API는 service-to-service 토큰으로 게이트. 토큰 미설정 시 비활성(기존 호환).
                .requestMatchers(SecurityPaths.INTERNAL_OPS_PATHS).access((authentication, context) ->
                    new AuthorizationDecision(
                        internalOpsAllowed(internalOpsToken, context.getRequest().getHeader(INTERNAL_OPS_TOKEN_HEADER))))
                .anyRequest().authenticated()
            )
            .exceptionHandling(eh -> eh.authenticationEntryPoint(entryPoint))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * /internal/ops 게이트 판정(#646): 토큰이 설정되지 않은 환경(빈 값)에서는 허용해 기존 배포 호환을
     * 유지하고, 설정된 경우에만 요청 헤더와 정확히 일치할 때 허용한다. gitops가 ai-service·ops-backend에
     * 동일 시크릿을 주입하면 외부 무인증 접근이 차단된다.
     */
    static boolean internalOpsAllowed(String expectedToken, String providedHeader) {
        return expectedToken == null || expectedToken.isBlank() || expectedToken.equals(providedHeader);
    }
}
