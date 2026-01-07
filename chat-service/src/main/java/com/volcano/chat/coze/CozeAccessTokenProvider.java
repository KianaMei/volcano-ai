package com.volcano.chat.coze;

import com.coze.openapi.client.auth.OAuthToken;
import com.coze.openapi.service.auth.JWTOAuthClient;
import com.volcano.chat.config.CozeConfig;
import com.volcano.chat.dto.CozeTokenResponse;
import com.volcano.chat.exception.TokenServiceException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Coze Access Token 提供者
 *
 * 纯粹负责调用 Coze OAuth API 获取 Token，不做缓存。
 * 缓存由 RedisUserTokenService 统一管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CozeAccessTokenProvider {

    private static final int TOKEN_DURATION_SECONDS = 1800; // 30分钟

    private final CozeConfig cozeConfig;

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

        log.info("CozeAccessTokenProvider initialized - OAuth: {}",
                oauthConfigured ? "configured" : "not configured");
    }

    /**
     * 检查 OAuth 是否已配置
     */
    public boolean isOAuthConfigured() {
        return oauthConfigured;
    }

    /**
     * 获取 Coze Access Token
     *
     * @param userUuid 用户标识（手机号），用作 session_name 实现会话隔离
     * @return CozeTokenResponse 包含 access_token 和过期时间
     */
    public CozeTokenResponse getAccessToken(String userUuid) {
        if (userUuid == null || userUuid.isEmpty()) {
            throw new TokenServiceException("userUuid is required for Coze OAuth");
        }

        if (!isOAuthConfigured()) {
            throw new TokenServiceException("OAuth is not configured");
        }

        try {
            JWTOAuthClient client = new JWTOAuthClient.JWTOAuthBuilder()
                    .clientID(cozeConfig.getOauthClientId())
                    .publicKey(cozeConfig.getOauthPublicKeyId())
                    .privateKey(this.privateKey)
                    .baseURL(cozeConfig.getApiBaseUrl())
                    .build();

            // 使用 userUuid（手机号）作为 session_name，实现会话隔离
            OAuthToken token = client.getAccessToken(TOKEN_DURATION_SECONDS, userUuid);
            int expiresIn = token.getExpiresIn();

            // 判断 expiresIn 是时间戳还是剩余秒数
            int remainingSeconds;
            if (expiresIn > 1000000000) {
                remainingSeconds = (int) (expiresIn - System.currentTimeMillis() / 1000);
                if (remainingSeconds < 0) {
                    remainingSeconds = 0;
                }
            } else {
                remainingSeconds = expiresIn;
            }

            log.info("Obtained Coze token for user: {}..., expires in: {}s",
                    userUuid.substring(0, Math.min(4, userUuid.length())), remainingSeconds);

            return new CozeTokenResponse(token.getAccessToken(), remainingSeconds);

        } catch (Exception e) {
            log.error("OAuth token generation failed for user {}: {}",
                    userUuid.substring(0, Math.min(4, userUuid.length())), e.getMessage());
            throw new TokenServiceException("OAuth token generation failed: " + e.getMessage(), e);
        }
    }
}
