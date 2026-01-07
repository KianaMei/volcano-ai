package com.volcano.chat.service.impl;

import com.volcano.chat.coze.CozeAccessTokenProvider;
import com.volcano.chat.dto.CozeTokenResponse;
import com.volcano.chat.dto.UserTokenInfo;
import com.volcano.chat.service.UserTokenService;
import com.volcano.chat.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的用户 Token 信息管理服务实现
 * 使用 Hash 结构存储：Key = JWT Token, Fields = {phone, cozeToken}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisUserTokenService implements UserTokenService {

    private final StringRedisTemplate redisTemplate;
    private final CozeAccessTokenProvider cozeAccessTokenProvider;

    // 过期时间设置为30分钟
    private static final long TOKEN_EXPIRE_SECONDS = 1800;
    private static final String KEY_PREFIX = "user:token:";
    
    private static final String FIELD_PHONE = "phone";
    private static final String FIELD_COZE_TOKEN = "cozeToken";

    @Override
    public String createUserToken(String phone) {
        if (phone == null || phone.isEmpty()) {
            throw new IllegalArgumentException("Phone cannot be null or empty");
        }
        
        // 使用 JwtUtil 生成 JWT Token
        String jwtToken = JwtUtil.generateToken(phone);
        String key = KEY_PREFIX + jwtToken;

        // 调用 CozeAccessTokenProvider 获取 Coze Token
        String cozeToken = fetchCozeToken(phone);

        // 使用 Hash 存储
        Map<String, String> tokenData = new HashMap<>();
        tokenData.put(FIELD_PHONE, phone);
        tokenData.put(FIELD_COZE_TOKEN, cozeToken != null ? cozeToken : "");

        redisTemplate.opsForHash().putAll(key, tokenData);
        redisTemplate.expire(key, TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);

        log.info("Created user token for phone: {}..., TTL: {}s",
                phone.substring(0, Math.min(4, phone.length())), TOKEN_EXPIRE_SECONDS);

        return jwtToken;
    }

    @Override
    public UserTokenInfo getUserTokenInfo(String jwtToken) {
        if (jwtToken == null || jwtToken.isEmpty()) {
            return null;
        }
        
        String key = KEY_PREFIX + jwtToken;
        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
        
        if (data.isEmpty()) {
            return null;
        }

        String phone = data.get(FIELD_PHONE) != null ? data.get(FIELD_PHONE).toString() : null;
        String cozeToken = data.get(FIELD_COZE_TOKEN) != null ? data.get(FIELD_COZE_TOKEN).toString() : null;
        
        return new UserTokenInfo(phone, cozeToken);
    }

    @Override
    public boolean deleteUserToken(String jwtToken) {
        if (jwtToken == null || jwtToken.isEmpty()) {
            return false;
        }
        
        String key = KEY_PREFIX + jwtToken;
        Boolean deleted = redisTemplate.delete(key);
        
        if (Boolean.TRUE.equals(deleted)) {
            log.info("Deleted user token: {}...", jwtToken.substring(0, 8));
            return true;
        }
        return false;
    }

    /**
     * 获取 Coze Token
     */
    private String fetchCozeToken(String phone) {
        try {
            CozeTokenResponse response = cozeAccessTokenProvider.getAccessToken(phone);
            return response.getAccessToken();
        } catch (Exception e) {
            log.error("Failed to fetch Coze token for phone: {}..., error: {}",
                    phone.substring(0, Math.min(4, phone.length())), e.getMessage());
            return null;
        }
    }
}
