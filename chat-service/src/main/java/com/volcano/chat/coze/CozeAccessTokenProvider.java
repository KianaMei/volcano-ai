package com.volcano.chat.coze;

import com.coze.openapi.client.auth.OAuthToken;
import com.coze.openapi.service.auth.JWTOAuthClient;
import com.volcano.chat.config.CozeConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Coze Access Token 提供者
 *
 * 直接集成 OAuth JWT 模式，按手机号(userUuid)缓存 access token 到 Redis。
 * 使用手机号作为 session_name 实现会话隔离。
 *
 * Redis 存储格式：
 * - Key: coze:token:{userUuid}
 * - Value: access_token
 * - TTL: token 的剩余有效期
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CozeAccessTokenProvider {

    private static final long REFRESH_BUFFER_SECONDS = 60; // 提前60秒刷新
    private static final long PAT_CONFIRMATION_EXPIRY_MS = 30 * 60 * 1000L; // PAT 确认有效期 30 分钟
    private static final String COZE_TOKEN_PREFIX = "coze:token:";

    private final CozeConfig cozeConfig;
    private final StringRedisTemplate redisTemplate;

    // 已确认使用 PAT 的 session（这个可以保留在内存，因为是临时确认状态）
    private final Set<String> patConfirmedSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> patConfirmationTime = new ConcurrentHashMap<>();

    private String privateKey;
    private boolean oauthConfigured = false;

    @PostConstruct
    public void init() {
        // 读取私钥文件
        String pkPath = cozeConfig.getOauthPrivateKeyPath();
        if (pkPath != null && !pkPath.isEmpty()) {
            try {
                this.privateKey = new String(Files.readAllBytes(Paths.get(pkPath)), StandardCharsets.UTF_8);
                log.info("Loaded OAuth private key from: {}", pkPath);
            } catch (IOException e) {
                log.warn("Cannot read private key file: {} - {}", pkPath, e.getMessage());
                this.privateKey = null;
            }
        }

        // 检查 OAuth 是否完整配置
        this.oauthConfigured = cozeConfig.getOauthClientId() != null
                && cozeConfig.getOauthPublicKeyId() != null
                && this.privateKey != null;

        log.info("CozeAccessTokenProvider initialized - OAuth: {}, PAT: {}, Storage: Redis",
                oauthConfigured ? "configured" : "not configured",
                isPatConfigured() ? "available" : "not configured");
    }

    /**
     * 检查 OAuth 是否已配置
     */
    public boolean isOAuthConfigured() {
        return oauthConfigured;
    }

    /**
     * 检查 PAT 是否已配置
     */
    public boolean isPatConfigured() {
        String pat = cozeConfig.getPatToken();
        return pat != null && !pat.isEmpty();
    }

    /**
     * 确认使用 PAT token
     */
    public void confirmUsePat(String userUuid) {
        if (!isPatConfigured()) {
            throw new TokenServiceException("PAT token not configured");
        }
        patConfirmedSessions.add(userUuid);
        patConfirmationTime.put(userUuid, System.currentTimeMillis());
        log.info("PAT usage confirmed for user: {}...", userUuid.substring(0, Math.min(4, userUuid.length())));
    }

    /**
     * 检查 PAT 确认是否有效
     */
    private boolean isPatConfirmed(String userUuid) {
        if (!patConfirmedSessions.contains(userUuid)) {
            return false;
        }
        Long confirmTime = patConfirmationTime.get(userUuid);
        if (confirmTime == null) {
            return false;
        }
        // 检查确认是否过期
        if (System.currentTimeMillis() - confirmTime > PAT_CONFIRMATION_EXPIRY_MS) {
            patConfirmedSessions.remove(userUuid);
            patConfirmationTime.remove(userUuid);
            return false;
        }
        return true;
    }

    /**
     * 获取 PAT token（需要先确认）
     */
    public TokenResponse getPatToken(String userUuid) {
        if (!isPatConfigured()) {
            throw new TokenServiceException("PAT token not configured");
        }
        if (!isPatConfirmed(userUuid)) {
            throw new TokenServiceException("PAT usage not confirmed for user: " + userUuid);
        }
        // PAT 没有过期时间，返回一个较长的 TTL
        return new TokenResponse(cozeConfig.getPatToken(), 7200, false, null);
    }

    /**
     * 从 Redis 获取缓存的 Coze Token
     */
    private String getCachedToken(String userUuid) {
        String key = COZE_TOKEN_PREFIX + userUuid;
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取缓存 Token 的剩余 TTL（秒）
     */
    private long getCachedTokenTTL(String userUuid) {
        String key = COZE_TOKEN_PREFIX + userUuid;
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null ? ttl : -1;
    }

    /**
     * 将 Coze Token 存入 Redis
     */
    private void cacheToken(String userUuid, String accessToken, int expiresInSeconds) {
        String key = COZE_TOKEN_PREFIX + userUuid;
        // 存入 Redis 并设置过期时间
        redisTemplate.opsForValue().set(key, accessToken, expiresInSeconds, TimeUnit.SECONDS);
        log.info("Coze token cached in Redis for user: {}..., TTL: {}s",
                userUuid.substring(0, Math.min(4, userUuid.length())), expiresInSeconds);
    }

    /**
     * 获取 Coze Access Token
     *
     * @param userUuid 用户标识（手机号），用作 session_name 实现会话隔离
     * @return TokenResponse 包含 access_token 或需要确认 PAT 的标志
     */
    public TokenResponse getAccessToken(String userUuid) {
        if (userUuid == null || userUuid.isEmpty()) {
            throw new TokenServiceException("userUuid is required for Coze OAuth");
        }

        // 检查 Redis 缓存
        String cachedToken = getCachedToken(userUuid);
        long ttl = getCachedTokenTTL(userUuid);

        // 如果缓存有效（剩余时间大于刷新缓冲期）
        if (cachedToken != null && ttl > REFRESH_BUFFER_SECONDS) {
            log.debug("Using cached Coze token from Redis for user: {}..., TTL: {}s",
                    userUuid.substring(0, Math.min(4, userUuid.length())), ttl);
            return new TokenResponse(cachedToken, (int) ttl, false, null);
        }

        // 如果 OAuth 未配置，检查是否有 PAT 可用
        if (!isOAuthConfigured()) {
            if (isPatConfigured()) {
                if (isPatConfirmed(userUuid)) {
                    return getPatToken(userUuid);
                } else {
                    return new TokenResponse(null, 0, true, "OAuth not configured, PAT available. Confirm to use PAT.");
                }
            } else {
                throw new TokenServiceException("Neither OAuth nor PAT is configured");
            }
        }

        // 尝试获取 OAuth token
        try {
            JWTOAuthClient client = new JWTOAuthClient.JWTOAuthBuilder()
                    .clientID(cozeConfig.getOauthClientId())
                    .publicKey(cozeConfig.getOauthPublicKeyId())
                    .privateKey(this.privateKey)
                    .baseURL(cozeConfig.getApiBaseUrl())
                    .build();

            // 使用 userUuid（手机号）作为 session_name，实现会话隔离
            OAuthToken token = client.getAccessToken(2700, userUuid); // 45分钟，带session
            int expiresIn = token.getExpiresIn();

            // 判断 expiresIn 是时间戳还是剩余秒数
            int remainingSeconds;
            if (expiresIn > 1000000000) {
                remainingSeconds = (int) (expiresIn - System.currentTimeMillis() / 1000);
                if (remainingSeconds < 0)
                    remainingSeconds = 0;
            } else {
                remainingSeconds = expiresIn;
            }

            // 存入 Redis
            cacheToken(userUuid, token.getAccessToken(), remainingSeconds);

            return new TokenResponse(token.getAccessToken(), remainingSeconds, false, null);

        } catch (Exception e) {
            log.error("OAuth token generation failed for user {}: {}",
                    userUuid.substring(0, Math.min(4, userUuid.length())), e.getMessage());

            // OAuth 失败，检查是否可以 fallback 到 PAT
            if (isPatConfigured()) {
                if (isPatConfirmed(userUuid)) {
                    log.info("OAuth failed, using confirmed PAT for user: {}...",
                            userUuid.substring(0, Math.min(4, userUuid.length())));
                    return getPatToken(userUuid);
                } else {
                    String errorMsg = "OAuth token generation failed: " + e.getMessage()
                            + ". PAT available, confirm to use.";
                    return new TokenResponse(null, 0, true, errorMsg);
                }
            } else {
                throw new TokenServiceException(
                        "OAuth token generation failed and no PAT configured: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 清除指定用户的 Coze Token 缓存
     */
    public void clearCache(String userUuid) {
        String key = COZE_TOKEN_PREFIX + userUuid;
        redisTemplate.delete(key);
        log.info("Cleared Coze token cache for user: {}...", userUuid.substring(0, Math.min(4, userUuid.length())));
    }

    /**
     * 清除所有 Coze Token 缓存
     */
    public void clearAllCache() {
        Set<String> keys = redisTemplate.keys(COZE_TOKEN_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Cleared all Coze token cache, {} keys deleted", keys.size());
        }
    }

    /**
     * Token Service 异常
     */
    public static class TokenServiceException extends RuntimeException {
        public TokenServiceException(String message) {
            super(message);
        }

        public TokenServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Token 响应
     */
    public static class TokenResponse {
        private final String accessToken;
        private final int expiresIn;
        private final boolean needsPatConfirmation;
        private final String message;

        public TokenResponse(String accessToken, int expiresIn, boolean needsPatConfirmation, String message) {
            this.accessToken = accessToken;
            this.expiresIn = expiresIn;
            this.needsPatConfirmation = needsPatConfirmation;
            this.message = message;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public int getExpiresIn() {
            return expiresIn;
        }

        public boolean isNeedsPatConfirmation() {
            return needsPatConfirmation;
        }

        public String getMessage() {
            return message;
        }
    }
}
