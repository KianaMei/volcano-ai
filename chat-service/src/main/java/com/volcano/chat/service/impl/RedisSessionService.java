package com.volcano.chat.service.impl;

import com.volcano.chat.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 会话服务实现
 * 强制使用 Redis 存储 Session，不支持回退到内存
 * 如果 Redis 未运行，应用将启动失败
 */
@Slf4j
@Service
public class RedisSessionService implements SessionService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Token 有效期：2小时
    private static final long TOKEN_EXPIRE_SECONDS = 7200;
    private static final String KEY_PREFIX = "chat:token:";

    @Override
    public String getUserUuid(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String key = KEY_PREFIX + token;
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public String createToken(String phone) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = KEY_PREFIX + token;

        // 存入 Redis 并设置过期时间
        redisTemplate.opsForValue().set(key, phone, TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        log.info("Created token for phone: {}..., stored in Redis with TTL {} seconds",
                phone.substring(0, Math.min(4, phone.length())), TOKEN_EXPIRE_SECONDS);

        return token;
    }
}
