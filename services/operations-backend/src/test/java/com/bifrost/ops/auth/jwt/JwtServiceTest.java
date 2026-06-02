package com.bifrost.ops.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "abcdef0123456789abcdef0123456789";

    @Test
    void issueAndParseRoundTrip() {
        JwtService jwtService = new JwtService(SECRET, "bifrost-test", 1);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = jwtService.issue(userId, tenantId, "user@example.com");
        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tid", String.class)).isEqualTo(tenantId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("user@example.com");
        assertThat(claims.getIssuer()).isEqualTo("bifrost-test");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void parseFailsWithWrongSecret() {
        JwtService jwtService = new JwtService(SECRET, "bifrost-test", 1);
        JwtService wrongKeyJwtService = new JwtService(OTHER_SECRET, "bifrost-test", 1);
        String token = jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), "user@example.com");

        assertThatThrownBy(() -> wrongKeyJwtService.parse(token))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void parseExpiredTokenThrowsJwtException() {
        JwtService jwtService = new JwtService(SECRET, "bifrost-test", -1);
        String token = jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), "user@example.com");

        assertThatThrownBy(() -> jwtService.parse(token))
            .isInstanceOf(JwtException.class);
    }
}
