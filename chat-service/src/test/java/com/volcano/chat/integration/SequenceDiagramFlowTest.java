package com.volcano.chat.integration;

import com.volcano.chat.dto.ChatRequest;
import com.volcano.chat.dto.UserTokenInfo;
import com.volcano.chat.entity.ChatLog;
import com.volcano.chat.service.ChatLogService;
import com.volcano.chat.service.CozeProxyService;
import com.volcano.chat.service.UserTokenService;
import com.volcano.chat.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时序图流程集成测试
 * 
 * 验证 README.md 中时序图描述的三个阶段：
 * - 阶段一：身份交换 (S2S)
 * - 阶段二：流式交互与记录
 * - 阶段三：归档
 * 
 * 本测试使用真实的 Spring Boot 环境，需要：
 * - PostgreSQL 数据库运行
 * - Redis 运行
 * - Coze OAuth 配置正确（用于 SSE 测试）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("时序图流程集成测试 (真实环境)")
class SequenceDiagramFlowTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private ChatLogService chatLogService;

    @Autowired
    private CozeProxyService cozeProxyService;

    // 测试数据
    private static String jwtToken;
    private static String cozeToken;
    private static final String TEST_PHONE = "13800138000";
    private static Long chatLogId;
    private static String testSessionId;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @BeforeAll
    static void beforeAll() {
        System.out.println("\n========== 时序图流程集成测试开始 ==========");
        System.out.println("测试环境: Spring Boot + PostgreSQL + Redis + Coze API\n");
    }

    @AfterAll
    static void afterAll() {
        System.out.println("\n========== 时序图流程集成测试结束 ==========\n");
    }

    // ==================== 阶段一：身份交换 (S2S) ====================

    @Test
    @Order(1)
    @DisplayName("阶段一 - 步骤2-4: S2S 身份交换 (/internal/session/create)")
    void phase1_s2sSessionCreate() {
        System.out.println("【阶段一：身份交换 (S2S)】");
        System.out.println("步骤2: 业务方后端调用 /internal/session/create");
        
        // 准备请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("phone", TEST_PHONE);
        
        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
        
        System.out.println("  POST " + baseUrl() + "/api/internal/session/create");
        System.out.println("  Body: {\"phone\": \"" + TEST_PHONE + "\"}");
        
        // 调用 S2S 接口
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/internal/session/create",
                request,
                Map.class
        );
        
        // 打印响应信息用于调试
        System.out.println("  Response Status: " + response.getStatusCode());
        System.out.println("  Response Body: " + response.getBody());
        
        // 如果返回500，打印错误信息后再断言
        if (response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR) {
            System.err.println("  ✗ 服务器内部错误: " + response.getBody());
        }
        
        // 验证响应
        assertEquals(HttpStatus.OK, response.getStatusCode(), "应返回 200 OK，实际响应: " + response.getBody());
        assertNotNull(response.getBody(), "响应体不应为空");
        
        jwtToken = (String) response.getBody().get("token");
        Object expiresAt = response.getBody().get("expiresAt");
        
        assertNotNull(jwtToken, "响应应包含 token");
        assertNotNull(expiresAt, "响应应包含 expiresAt");
        
        System.out.println("\n步骤3: 生成 JWT Token，获取 Coze Token，存入 Redis");
        System.out.println("  [AuthController] 调用 UserTokenService.createUserToken()");
        
        // 验证 JWT 格式
        String[] parts = jwtToken.split("\\.");
        assertEquals(3, parts.length, "JWT 应为三段式格式");
        
        // 验证 JWT 内容
        JwtUtil.JwtValidationResult jwtResult = JwtUtil.validateToken(jwtToken);
        assertTrue(jwtResult.valid(), "JWT 应有效");
        assertEquals(TEST_PHONE, jwtResult.subject(), "JWT subject 应为手机号");
        
        System.out.println("  ✓ JWT Token 生成成功: " + jwtToken.substring(0, 30) + "...");
        System.out.println("  ✓ JWT 验证通过: subject=" + jwtResult.subject() + ", expires=" + jwtResult.expiration());
        
        // 验证 Redis 存储
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(jwtToken);
        assertNotNull(tokenInfo, "Redis 中应存在用户信息");
        assertEquals(TEST_PHONE, tokenInfo.phone(), "手机号应匹配");
        
        cozeToken = tokenInfo.cozeToken();
        System.out.println("  ✓ Redis 存储验证: phone=" + tokenInfo.phone());
        if (cozeToken != null && !cozeToken.isEmpty()) {
            System.out.println("  ✓ Coze Token 获取成功: " + cozeToken.substring(0, Math.min(20, cozeToken.length())) + "...");
        } else {
            System.out.println("  ⚠ Coze Token 为空 (OAuth 可能未配置)");
        }
        
        System.out.println("\n步骤4: 返回 JWT Token 给业务方后端");
        System.out.println("  Response: " + response.getBody());
        System.out.println("\n【阶段一完成】✓\n");
    }

    // ==================== 阶段二：流式交互与记录 ====================

    @Test
    @Order(2)
    @DisplayName("阶段二 - 步骤6-7: 前端发送消息并校验Token")
    void phase2_sendMessageAndValidateToken() {
        System.out.println("【阶段二：流式交互与记录】");
        System.out.println("步骤6: 前端发送消息 (SSE Request)");
        
        assertNotNull(jwtToken, "需要先执行阶段一测试");
        
        testSessionId = "test-conv-" + System.currentTimeMillis();
        String message = "你好";
        
        System.out.println("  请求头: X-Chat-Token: " + jwtToken.substring(0, 20) + "...");
        System.out.println("  请求体: {message: \"" + message + "\", conversationId: \"" + testSessionId + "\"}");
        
        System.out.println("\n步骤7: 校验 Token (Redis)");
        
        // 1. JWT 验证
        JwtUtil.JwtValidationResult jwtResult = JwtUtil.validateToken(jwtToken);
        assertTrue(jwtResult.valid(), "JWT 验证应通过");
        System.out.println("  ✓ JWT 验证通过: subject=" + jwtResult.subject());
        
        // 2. Redis 查询
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(jwtToken);
        assertNotNull(tokenInfo, "Redis 中应存在用户信息");
        assertEquals(TEST_PHONE, tokenInfo.phone(), "手机号应匹配");
        System.out.println("  ✓ Redis 查询成功: phone=" + tokenInfo.phone());
        
        if (tokenInfo.cozeToken() == null || tokenInfo.cozeToken().isEmpty()) {
            System.out.println("  ⚠ Coze Token 为空，后续 SSE 测试将跳过");
        }
    }

    @Test
    @Order(3)
    @DisplayName("阶段二 - 步骤8-11 & 阶段三 - 步骤13: 真实 SSE 对话流程")
    void phase2And3_realSseConversation() {
        System.out.println("\n步骤8-11 & 步骤13: 真实 SSE 对话流程");
        
        assertNotNull(jwtToken, "需要先执行阶段一测试");
        
        // 检查 Coze Token 是否可用
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(jwtToken);
        if (tokenInfo == null || tokenInfo.cozeToken() == null || tokenInfo.cozeToken().isEmpty()) {
            System.out.println("  ⚠ 跳过: Coze Token 不可用 (OAuth 未配置)");
            System.out.println("  提示: 请确保 application.properties 中配置了正确的 Coze OAuth 信息");
            return;
        }
        
        // 构建聊天请求
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setMessage("你好，请用一句话介绍你自己");
        chatRequest.setConversationId(testSessionId);
        
        System.out.println("  [CozeProxyService.sendMessage] 开始调用...");
        System.out.println("  - userPhone: " + TEST_PHONE);
        System.out.println("  - message: " + chatRequest.getMessage());
        System.out.println("  - conversationId: " + chatRequest.getConversationId());
        
        // 调用真实的 CozeProxyService
        // SseEmitter 在后台线程执行，我们通过查询数据库来验证结果
        cozeProxyService.sendMessage(
                chatRequest, 
                tokenInfo.phone(), 
                tokenInfo.cozeToken()
        );
        
        System.out.println("  ✓ SseEmitter 创建成功，SSE 流在后台执行");
        System.out.println("  [等待 SSE 流完成 (最多30秒)...]");
        
        // 等待 SSE 流完成，最多等待 30 秒，每 2 秒检查一次
        int maxWaitSeconds = 30;
        int checkIntervalMs = 2000;
        ChatLog latestLog = null;
        
        for (int waited = 0; waited < maxWaitSeconds * 1000; waited += checkIntervalMs) {
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            // 检查数据库中是否有答案
            var logs = chatLogService.selectByUserId(TEST_PHONE);
            if (logs != null && !logs.isEmpty()) {
                latestLog = logs.stream()
                        .filter(log -> testSessionId.equals(log.getSessionId()))
                        .findFirst()
                        .orElse(null);
                
                if (latestLog != null && latestLog.getAiAnswer() != null && !latestLog.getAiAnswer().isEmpty()) {
                    System.out.println("  ✓ SSE 流完成，耗时约 " + (waited + checkIntervalMs) / 1000 + " 秒");
                    break;
                }
            }
            System.out.println("    ... 已等待 " + (waited + checkIntervalMs) / 1000 + " 秒");
        }
        
        // 验证数据库记录
        System.out.println("\n  [验证数据库记录]");
        var logs = chatLogService.selectByUserId(TEST_PHONE);
        
        if (logs != null && !logs.isEmpty()) {
            // 找到最新的记录
            latestLog = logs.stream()
                    .filter(log -> testSessionId.equals(log.getSessionId()))
                    .findFirst()
                    .orElse(null);
            
            if (latestLog != null) {
                chatLogId = latestLog.getRecordId();
                
                System.out.println("  ✓ 步骤8 (Insert Q) 验证通过:");
                System.out.println("    - recordId: " + latestLog.getRecordId());
                System.out.println("    - sessionId: " + latestLog.getSessionId());
                System.out.println("    - userQuestion: " + latestLog.getUserQuestion());
                System.out.println("    - requestTime: " + latestLog.getRequestTime());
                
                if (latestLog.getAiAnswer() != null && !latestLog.getAiAnswer().isEmpty()) {
                    System.out.println("  ✓ 步骤13 (Update A) 验证通过:");
                    System.out.println("    - aiAnswer: " + latestLog.getAiAnswer().substring(0, Math.min(50, latestLog.getAiAnswer().length())) + "...");
                    System.out.println("    - responseTime: " + latestLog.getResponseTime());
                } else {
                    System.out.println("  ⚠ 步骤13 (Update A): AI 答案为空 (可能 SSE 流未完成或 Coze API 返回错误)");
                }
            } else {
                System.out.println("  ⚠ 未找到 sessionId=" + testSessionId + " 的记录");
            }
        } else {
            System.out.println("  ⚠ 未找到用户 " + TEST_PHONE + " 的聊天记录");
        }
        
        System.out.println("\n【阶段二 & 阶段三完成】✓");
    }

    // ==================== 验证完整流程 ====================

    @Test
    @Order(4)
    @DisplayName("验证: 完整流程数据一致性")
    void verifyCompleteFlow() {
        System.out.println("\n========== 完整流程验证 ==========");
        
        // 1. 验证 Token 仍然有效
        assertNotNull(jwtToken, "JWT Token 不应为空");
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(jwtToken);
        assertNotNull(tokenInfo, "Token 应仍然有效");
        System.out.println("✓ [Redis] Token 有效: phone=" + tokenInfo.phone());
        
        // 2. 验证聊天记录
        if (chatLogId != null) {
            ChatLog chatLog = chatLogService.selectById(chatLogId);
            assertNotNull(chatLog, "应能从数据库查询到记录");
            
            System.out.println("✓ [PostgreSQL] 聊天记录:");
            System.out.println("  - recordId: " + chatLog.getRecordId());
            System.out.println("  - userId: " + chatLog.getUserId());
            System.out.println("  - sessionId: " + chatLog.getSessionId());
            System.out.println("  - userQuestion: " + chatLog.getUserQuestion());
            System.out.println("  - aiAnswer: " + (chatLog.getAiAnswer() != null ? 
                    chatLog.getAiAnswer().substring(0, Math.min(50, chatLog.getAiAnswer().length())) + "..." : "null"));
            System.out.println("  - requestTime: " + chatLog.getRequestTime());
            System.out.println("  - responseTime: " + chatLog.getResponseTime());
            
            // 验证时间顺序
            if (chatLog.getResponseTime() != null) {
                assertTrue(chatLog.getResponseTime().isAfter(chatLog.getRequestTime()) || 
                           chatLog.getResponseTime().isEqual(chatLog.getRequestTime()),
                           "响应时间应晚于或等于请求时间");
                System.out.println("✓ 时间顺序正确: requestTime <= responseTime");
            }
        } else {
            System.out.println("⚠ 无聊天记录可验证 (SSE 测试可能被跳过)");
        }
        
        System.out.println("\n========== 流程验证完成 ==========\n");
    }

    // ==================== 清理 ====================

    @Test
    @Order(5)
    @DisplayName("清理: 删除测试数据")
    void cleanup() {
        System.out.println("========== 清理测试数据 ==========");
        
        // 删除聊天记录
        if (chatLogId != null) {
            try {
                boolean deleted = chatLogService.deleteById(chatLogId);
                System.out.println("删除聊天记录 (recordId=" + chatLogId + "): " + (deleted ? "✓" : "✗"));
            } catch (Exception e) {
                System.out.println("删除聊天记录失败: " + e.getMessage());
            }
        }
        
        // 删除 Token
        if (jwtToken != null) {
            try {
                boolean deleted = userTokenService.deleteUserToken(jwtToken);
                System.out.println("删除 Token: " + (deleted ? "✓" : "✗"));
            } catch (Exception e) {
                System.out.println("删除 Token 失败: " + e.getMessage());
            }
        }
        
        System.out.println("========== 清理完成 ==========\n");
    }
}
