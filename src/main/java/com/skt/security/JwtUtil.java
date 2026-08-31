package com.skt.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey getKey() {
        // 显式指定 UTF-8，避免依赖平台默认编码导致跨环境签名不一致
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long id, String username, String role, String displayName) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", id);
        claims.put("username", username);
        claims.put("role", role);
        claims.put("displayName", displayName);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT 令牌验证失败: {}", e.getMessage());
            return false;
        }
    }

    public Long getUserId(String token) {
        Claims c = parseToken(token);
        return c.get("id", Long.class);
    }

    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public String getDisplayName(String token) {
        return parseToken(token).get("displayName", String.class);
    }
}
