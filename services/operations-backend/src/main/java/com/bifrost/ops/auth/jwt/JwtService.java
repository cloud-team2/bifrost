package com.bifrost.ops.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.issuer:bifrost-ops}") String issuer,
                      @Value("${jwt.expiration-hours:24}") long expirationHours) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "jwt.secret 은 최소 " + MIN_SECRET_BYTES + " 바이트(256bit) 이상이어야 합니다. 현재: "
                    + keyBytes.length + " 바이트");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = issuer;
        this.ttl = Duration.ofHours(expirationHours);
    }

    public String issue(UUID userId, UUID tenantId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(issuer)
            .subject(userId.toString())
            .claim("tid", tenantId.toString())
            .claim("email", email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser()
            .requireIssuer(issuer)
            .verifyWith(key)
            .build()
            .parseSignedClaims(token);
        return jws.getPayload();
    }

    public Duration ttl() {
        return ttl;
    }
}
