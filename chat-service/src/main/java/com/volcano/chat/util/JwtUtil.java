package com.volcano.chat.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 工具类
 * 负责生成和验证 JWT Token
 */
@Slf4j
public class JwtUtil {

    // JWT 有效时间：30分钟
    private static final long EXPIRE_TIME_MS = 30 * 60 * 1000L;

    // 密钥（生产环境应从配置文件读取）
    private static final String SECRET = "volcano-chat-service-jwt-secret-key-2024";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /**
     * 生成 JWT Token
     *
     * @param subject 主题（如用户手机号）
     * @return JWT Token
     */
    public static String generateToken(String subject) {
        Date now = new Date();
        Date expireTime = new Date(now.getTime() + EXPIRE_TIME_MS);

        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expireTime)
                .signWith(SECRET_KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 验证 JWT Token
     *
     * @param token JWT Token
     * @return 验证结果
     */
    public static JwtValidationResult validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return JwtValidationResult.invalid("Token is empty");
        }

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(SECRET_KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            return JwtValidationResult.valid(claims.getSubject(), claims.getIssuedAt(), claims.getExpiration());
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
            return JwtValidationResult.invalid("Token expired");
        } catch (Exception e) {
            log.warn("JWT token validation failed: {}", e.getMessage());
            return JwtValidationResult.invalid("Invalid token");
        }
    }

    /**
     * JWT 验证结果
     */
    public record JwtValidationResult(
            boolean valid,
            String subject,
            Date issuedAt,
            Date expiration,
            String errorMessage
    ) {
        public static JwtValidationResult valid(String subject, Date issuedAt, Date expiration) {
            return new JwtValidationResult(true, subject, issuedAt, expiration, null);
        }

        public static JwtValidationResult invalid(String errorMessage) {
            return new JwtValidationResult(false, null, null, null, errorMessage);
        }
    }
}
