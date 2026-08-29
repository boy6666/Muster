package com.muster.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JwtService.class);

    /** 与 application.yml 的开发默认值一致；线上若仍为此值，令牌可被任何读过源码的人伪造。 */
    static final String DEV_SECRET = "muster-dev-secret-0123456789abcdef0123456789abcdef";

    private final SecretKey key;
    private final Duration ttl;
    private final java.time.Clock clock;

    @Autowired
    public JwtService(@Value("${muster.jwt-secret}") String secret, java.time.Clock clock) {
        this(secret, Duration.ofDays(7), clock);
        if (DEV_SECRET.equals(secret)) {
            log.warn("JWT 正在使用内置开发密钥：请通过环境变量 JWT_SECRET 设置随机密钥，否则管理员令牌可被伪造");
        }
    }

    JwtService(String secret, Duration ttl) {
        this(secret, ttl, java.time.Clock.systemUTC());
    }

    JwtService(String secret, Duration ttl, java.time.Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(Long adminId, String username) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(String.valueOf(adminId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    public Optional<String> parseUsername(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.ofNullable(claims.get("username", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
