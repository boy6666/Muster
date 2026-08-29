package com.muster.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789abcdef";

    private final JwtService service = new JwtService(SECRET);

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
}
