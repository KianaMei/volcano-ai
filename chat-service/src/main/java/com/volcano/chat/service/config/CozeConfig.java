package com.volcano.chat.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Coze API 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "coze")
public class CozeConfig {

    // ============ OAuth JWT 模式配置 ============

    /**
     * OAuth Client ID（JWT 模式）
     */
    private String oauthClientId;

    /**
     * OAuth Public Key ID（JWT 模式）
     */
    private String oauthPublicKeyId;

    /**
     * OAuth Private Key 文件路径（JWT 模式）
     */
    private String oauthPrivateKeyPath;

    // ============ PAT Token 备用配置 ============

    /**
     * PAT Token（备用，需用户确认后才会使用）
     */
    private String patToken;

    // ============ 业务配置 ============

    /**
     * Workflow ID
     */
    private String workflowId;

    /**
     * App ID
     */
    private String appId;

    /**
     * Bot ID
     */
    private String botId;

    /**
     * Voice ID for TTS
     */
    private String voiceId;

    /**
     * API Base URL
     */
    private String apiBaseUrl = "https://api.coze.cn";
}
