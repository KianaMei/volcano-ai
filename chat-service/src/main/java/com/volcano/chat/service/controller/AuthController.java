package com.volcano.chat.service.controller;

import com.volcano.chat.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private SessionService sessionService;

    // 内部 S2S 接口：供网站A后端调用
    // 真实场景下应添加 IP 白名单或各服务间鉴权
    @PostMapping("/internal/session/create")
    public Map<String, String> createSession(@RequestBody Map<String, String> request) {
        String phone = request.get("phone");
        if (phone == null || phone.isEmpty()) {
            throw new IllegalArgumentException("Phone is required");
        }

        String token = sessionService.createToken(phone);

        Map<String, String> response = new HashMap<>();
        response.put("token", token);
        return response;
    }

    // ==========================================
    // Mock 接口：模拟 网站A 的后端
    // 前端直接调这个接口，假装自己通过了 Cookie 验证
    // ==========================================
    @PostMapping("/mock/website-a/init")
    public Map<String, Object> mockWebsiteAInit() {
        // 模拟网站A后端逻辑：
        // 1. 验证 Cookie... (省略)
        // 2. 获取到当前登录用户是 "13800138000"
        String currentUserPhone = "13800138000";

        // 3. 调用 S2S 接口生成 Token
        // (这里直接调用 Service 模拟远程调用)
        String token = sessionService.createToken(currentUserPhone);

        // Token 有效期（毫秒）
        long tokenExpireMs = 7200 * 1000L;

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("expiresAt", System.currentTimeMillis() + tokenExpireMs);
        return response;
    }
}
