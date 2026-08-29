package com.muster.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789abcdef";

    private final JwtService service = new JwtService(SECRET, Duration.ofDays(7));

    @Test
    void roundTripIssueAndParse() {
        String token = service.issue(1L, "admin");
        assertThat(service.parseUsername(token)).isEqualTo(Optional.of("admin"));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = service.issue(1L, "admin");
        assertThat(service.parseUsername(token + "x")).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expired = new JwtService(SECRET, Duration.ofSeconds(-10));
        String token = expired.issue(1L, "admin");
        assertThat(expired.parseUsername(token)).isEmpty();
    }

    @Test
    void garbageTokenIsRejected() {
        assertThat(service.parseUsername("not-a-jwt")).isEmpty();
    }

    @Test
    void issuedAtComesFromInjectedClock() {
        // 项目约定"所有当前时间用注入的 Clock"；JWT 签发时间也应来自 Clock，便于测试拨钟
        java.time.Instant fixed = java.time.Instant.parse("2026-01-01T00:00:00Z");
        java.time.Clock clock = java.time.Clock.fixed(fixed, java.time.ZoneOffset.UTC);
        JwtService clocked = new JwtService(SECRET, Duration.ofDays(7), clock);

        String token = clocked.issue(1L, "admin");
        long issuedAtSeconds = io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .clock(() -> java.util.Date.from(clock.instant()))
                .build().parseSignedClaims(token).getPayload().getIssuedAt().toInstant().getEpochSecond();
        assertThat(issuedAtSeconds).isEqualTo(fixed.getEpochSecond());
    }
}
