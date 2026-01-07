package com.volcano.chat.integration;

import com.volcano.chat.dto.UserTokenInfo;
import com.volcano.chat.entity.ChatLog;
import com.volcano.chat.service.ChatLogService;
import com.volcano.chat.service.UserTokenService;
import com.volcano.chat.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实环境时序图流程集成测试
 * 
 * 需要运行的外部服务：
 * - Redis (localhost:6379)
 * - PostgreSQL/MySQL (根据 application.properties 配置)
 * 
 * 注意：Coze OAuth 需要有效配置才能完整测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("真实环境时序图流程测试")
class RealSequenceDiagramFlowTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private ChatLogService chatLogService;

    private static String jwtToken;
    private static final String TEST_PHONE = "13800138000";
    private static Long chatLogId;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    // ==================== 阶段一：身份交换 (S2S) ====================

    @Test
    @Order(1)
    @DisplayName("阶段一: S2S 身份交换 - /internal/session/create")
    void phase1_s2sSessionCreate() {
        System.out.println("\n========== 阶段一：身份交换 (S2S) ==========");
        
        // 准备请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("phone", TEST_PHONE);
        
        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
        
        // 调用 S2S 接口
        System.out.println("POST " + baseUrl() + "/api/internal/session/create");
        System.out.println("Body: {\"phone\": \"" + TEST_PHONE + "\"}");
        
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/internal/session/create",
                request,
                Map.class
        );
        
        // 验证响应
        assertEquals(HttpStatus.OK, response.getStatusCode(), "应返回 200 OK");
        assertNotNull(response.getBody());
        
        jwtToken = (String) response.getBody().get("token");
        Object expiresAt = response.getBody().get("expiresAt");
        
        assertNotNull(jwtToken, "响应应包含 token");
        assertNotNull(expiresAt, "响应应包含 expiresAt");
        
        System.out.println("Response: " + response.getBody());
        System.out.println("✓ JWT Token 获取成功: " + jwtToken.substring(0, Math.min(30, jwtToken.length())) + "...");
        
        // 验证 JWT 格式
        String[] parts = jwtToken.split("\\.");
        assertEquals(3, parts.length, "JWT 应为三段式格式");
        
        // 验证 JWT 内容
        JwtUtil.JwtValidationResult jwtResult = JwtUtil.validateToken(jwtToken);
        assertTrue(jwtResult.valid(), "JWT 应有效");
        assertEquals(TEST_PHONE, jwtResult.subject(), "JWT subject 应为手机号");
        
        System.out.println("✓ JWT 验证通过: subject=" + jwtResult.subject() + ", expires=" + jwtResult.expiration());
    }

    @Test
    @Order(2)
    @DisplayName("阶段一: 验证 Redis 存储")
    void phase1_verifyRedisStorage() {
        assertNotNull(jwtToken, "需要先执行 phase1_s2sSessionCreate");
        
        // 从 Redis 获取用户信息
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(jwtToken);
        
        assertNotNull(tokenInfo, "Redis 中应存在用户信息");
        assertEquals(TEST_PHONE, tokenInfo.phone(), "手机号应匹配");
        
        // Coze Token 可能为空（如果 OAuth 未配置）
        System.out.println("Redis 存储验证:");
        System.out.println("  - phone: " + tokenInfo.phone());
        System.out.println("  - cozeToken: " + (tokenInfo.cozeToken() != null ? 
                tokenInfo.cozeToken().substring(0, Math.min(20, tokenInfo.cozeToken().length())) + "..." : "null (OAuth未配置)"));
        System.out.println("✓ Redis 存储验证通过");
        System.out.println("\n========== 阶段一完成 ✓ ==========\n");
    }

    // ==================== 阶段二：流式交互与记录 ====================

    @Test
    @Order(3)
    @DisplayName("阶段二: Token 校验")
    void phase2_tokenValidation() {
        System.out.println("========== 阶段二：流式交互与记录 ==========");
        assertNotNull(jwtToken, "需要先执行阶段一测试");
        
        // 模拟 ChatController.validateToken() 逻辑
        
        // 1. JWT 验证
        JwtUtil.JwtValidationResult jwtResult = JwtUtil.validateToken(jwtToken);
        assertTrue(jwtResult.valid(), "JWT 验证应通过");
        System.out.println("步骤7: Token 校验");
        System.out.println("  - JWT 验证: ✓");
        
        // 2. Redis 查询
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(jwtToken);
        assertNotNull(tokenInfo, "Redis 中应存在用户信息");
        System.out.println("  - Redis 查询: ✓ phone=" + tokenInfo.phone());
        System.out.println("✓ Token 校验通过");
    }

    @Test
    @Order(4)
    @DisplayName("阶段二: 步骤8 - 插入问题记录 (Insert Q)")
    void phase2_insertQuestion() {
        System.out.println("\n步骤8: 将提问写入 MySQL (Insert Q)");
        
        LocalDateTime requestTime = LocalDateTime.now();
        String userQuestion = "测试问题 - " + System.currentTimeMillis();
        String sessionId = "test-session-" + System.currentTimeMillis();
        
        // 插入问题记录（答案为空）
        ChatLog chatLog = new ChatLog();
        chatLog.setUserId(TEST_PHONE);
        chatLog.setSessionId(sessionId);
        chatLog.setUserQuestion(userQuestion);
        chatLog.setAiAnswer(null);
        chatLog.setRequestTime(requestTime);
        chatLog.setResponseTime(null);
        chatLog.setDeleted(0);
        
        chatLogId = chatLogService.insert(chatLog);
        
        assertNotNull(chatLogId, "插入应返回有效的 recordId");
        
        // 验证记录
        ChatLog inserted = chatLogService.selectById(chatLogId);
        assertNotNull(inserted);
        assertEquals(userQuestion, inserted.getUserQuestion());
        assertNull(inserted.getAiAnswer(), "此时答案应为 null");
        
        System.out.println("  - recordId: " + chatLogId);
        System.out.println("  - question: " + userQuestion);
        System.out.println("  - answer: null (待更新)");
        System.out.println("✓ 问题记录插入成功");
    }

    // ==================== 阶段三：归档 ====================

    @Test
    @Order(5)
    @DisplayName("阶段三: 步骤13 - 更新答案 (Update A)")
    void phase3_updateAnswer() {
        System.out.println("\n========== 阶段三：归档 ==========");
        System.out.println("步骤13: 将回答写入 MySQL (Update A)");
        
        assertNotNull(chatLogId, "需要先执行 phase2_insertQuestion");
        
        String aiAnswer = "这是 AI 的测试回答 - " + System.currentTimeMillis();
        LocalDateTime responseTime = LocalDateTime.now();
        
        // 更新答案
        ChatLog updateLog = new ChatLog();
        updateLog.setRecordId(chatLogId);
        updateLog.setAiAnswer(aiAnswer);
        updateLog.setResponseTime(responseTime);
        
        boolean updated = chatLogService.updateById(updateLog);
        assertTrue(updated, "更新应成功");
        
        // 验证更新结果
        ChatLog result = chatLogService.selectById(chatLogId);
        assertNotNull(result);
        assertNotNull(result.getUserQuestion());
        assertEquals(aiAnswer, result.getAiAnswer());
        assertNotNull(result.getResponseTime());
        
        System.out.println("  - recordId: " + chatLogId);
        System.out.println("  - answer: " + aiAnswer);
        System.out.println("  - responseTime: " + responseTime);
        System.out.println("✓ 答案更新成功");
        System.out.println("\n========== 阶段三完成 ✓ ==========\n");
    }

    // ==================== 清理 ====================

    @Test
    @Order(6)
    @DisplayName("清理: 删除测试数据")
    void cleanup() {
        System.out.println("========== 清理测试数据 ==========");
        
        // 删除聊天记录
        if (chatLogId != null) {
            boolean deleted = chatLogService.deleteById(chatLogId);
            System.out.println("删除聊天记录 (recordId=" + chatLogId + "): " + (deleted ? "✓" : "✗"));
        }
        
        // 删除 Token
        if (jwtToken != null) {
            boolean deleted = userTokenService.deleteUserToken(jwtToken);
            System.out.println("删除 Token: " + (deleted ? "✓" : "✗"));
        }
        
        System.out.println("========== 清理完成 ==========\n");
    }

    // ==================== Mock 接口测试 ====================

    @Test
    @Order(10)
    @DisplayName("Mock接口: /mock/website-a/init")
    void testMockWebsiteAInit() {
        System.out.println("\n========== Mock 接口测试 ==========");
        
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/mock/website-a/init",
                null,
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("token"));
        assertNotNull(response.getBody().get("expiresAt"));
        
        System.out.println("POST /api/mock/website-a/init");
        System.out.println("Response: " + response.getBody());
        System.out.println("✓ Mock 接口测试通过");
        
        // 清理
        String mockToken = (String) response.getBody().get("token");
        if (mockToken != null) {
            userTokenService.deleteUserToken(mockToken);
        }
    }
}
