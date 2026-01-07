package com.volcano.chat.controller;

import com.volcano.chat.service.UserTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 * 
 * 时序图阶段一：身份交换 (S2S)
 * 1. 业务方后端调用 /internal/session/create
 * 2. 生成 JWT Token（记录生成时间和过期时间）
 * 3. 使用手机号申请 Coze Token
 * 4. 将手机号和 Coze Token 存入 Redis
 * 5. 返回 JWT Token 作为凭证
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AuthController {

    private final UserTokenService userTokenService;

    // Token 有效期：30分钟
    private static final long TOKEN_EXPIRE_MS = 1800 * 1000L;

    /**
     * 内部 S2S 接口：供业务方后端调用
     * 
     * 流程：
     * 1. 生成 JWT Token（包含生成时间和过期时间）
     * 2. 使用 phone 申请 Coze Token
     * 3. 将 {phone, cozeToken} 存入 Redis，Key 为 JWT Token
     * 4. 返回 JWT Token
     * 
     * 真实场景下应添加 IP 白名单或服务间鉴权
     */
    @PostMapping("/internal/session/create")
    public Map<String, Object> createSession(@RequestBody Map<String, String> request) {
        String phone = request.get("phone");
        if (phone == null || phone.isEmpty()) {
            throw new IllegalArgumentException("Phone is required");
        }

        // 生成 JWT Token，同时获取 Coze Token 并存入 Redis
        String jwtToken = userTokenService.createUserToken(phone);

        Map<String, Object> response = new HashMap<>();
        response.put("token", jwtToken);
        response.put("expiresAt", System.currentTimeMillis() + TOKEN_EXPIRE_MS);
        return response;
    }

    /**
     * Mock 接口：模拟业务方后端
     * 前端直接调这个接口，假装自己通过了 Cookie 验证
     */
    @PostMapping("/mock/website-a/init")
    public Map<String, Object> mockWebsiteAInit() {
        // 模拟业务方后端逻辑：
        // 1. 验证 Cookie... (省略)
        // 2. 获取到当前登录用户是 "13800138000"
        String currentUserPhone = "13800138000";

        // 3. 调用 UserTokenService 生成 JWT Token
        //    内部会：生成JWT → 获取CozeToken → 存入Redis
        String jwtToken = userTokenService.createUserToken(currentUserPhone);

        Map<String, Object> response = new HashMap<>();
        response.put("token", jwtToken);
        response.put("expiresAt", System.currentTimeMillis() + TOKEN_EXPIRE_MS);
        return response;
    }
}
