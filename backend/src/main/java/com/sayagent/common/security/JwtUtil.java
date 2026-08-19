package com.sayagent.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 门禁卡机：登录成功发 token（含 username + role，2h 有效）；闸机拿 token 验真伪与过期。
 * 密钥走配置 ${sayagent.jwt.secret}（§9 不硬编码，生产用环境变量注入，至少 256bit）。
 */
@Component
public class JwtUtil {

    private static final long DEFAULT_EXPIRATION_MS = 7_200_000L;

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(@Value("${sayagent.jwt.secret}") String secret,
                   @Value("${sayagent.jwt.expiration-ms:" + DEFAULT_EXPIRATION_MS + "}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /** 制卡：把用户名与角色写进 token。 */
    public String sign(String username, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /** 验卡：返回用户名；token 伪造/过期则抛 JwtException（由 AuthFilter 转 401）。 */
    public String parseUsername(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public String parseRole(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("role", String.class);
    }
}
