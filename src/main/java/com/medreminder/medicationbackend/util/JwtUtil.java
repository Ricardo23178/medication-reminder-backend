package com.medreminder.medicationbackend.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：生成 token + 解析校验 token。
 * 登录发 token、拦截器验 token 都走这里，保证两处规则一致。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-hours}")
    private long expireHours;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public long getExpireSeconds() {
        return expireHours * 3600;
    }

    /** 生成 token，显式 HS256（HMAC-SHA256） */
    public String generate(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))   // sub = 用户ID
                .claim("role", role)               // 自定义字段：角色
                .issuedAt(new Date())              // iat = 签发时间
                .expiration(new Date(System.currentTimeMillis() + expireHours * 3600_000L)) // exp
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验 token。校验签名、有效期，失败抛出 io.jsonwebtoken 异常。
     * 调用方用 try/catch 接住即可。
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
