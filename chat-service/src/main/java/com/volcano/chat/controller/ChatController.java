package com.volcano.chat.controller;

import com.volcano.chat.dto.ChatRequest;
import com.volcano.chat.dto.CancelChatRequest;
import com.volcano.chat.dto.UserTokenInfo;
import com.volcano.chat.service.CozeProxyService;
import com.volcano.chat.service.UserTokenService;
import com.volcano.chat.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 聊天控制器
 * 代理前端请求到 Coze API，隐藏敏感信息（user_uuid、token 等）
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ChatController {

    private final CozeProxyService cozeProxyService;
    private final UserTokenService userTokenService;

    /**
     * 验证 JWT Token 并获取用户信息
     */
    private UserTokenInfo validateToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Missing X-Chat-Token header");
        }
        
        // 验证 JWT 签名和过期时间
        JwtUtil.JwtValidationResult jwtResult = JwtUtil.validateToken(token);
        if (!jwtResult.valid()) {
            throw new IllegalArgumentException(jwtResult.errorMessage());
        }
        
        // 从 Redis 获取用户信息
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(token);
        if (tokenInfo == null) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        if (tokenInfo.cozeToken() == null || tokenInfo.cozeToken().isEmpty()) {
            throw new IllegalArgumentException("Coze token not available");
        }
        
        return tokenInfo;
    }

    /**
     * 发送消息（SSE 流式响应）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @RequestBody ChatRequest request,
            @RequestHeader("X-Chat-Token") String token) {
        UserTokenInfo tokenInfo;
        try {
            tokenInfo = validateToken(token);
        } catch (IllegalArgumentException e) {
            return sendErrorEmitter(e.getMessage());
        }

        log.info("Received chat request for user: {}...", 
                tokenInfo.phone().substring(0, Math.min(4, tokenInfo.phone().length())));

        return cozeProxyService.sendMessage(request, tokenInfo.phone(), tokenInfo.cozeToken());
    }

    /**
     * 取消进行中的对话
     */
    @PostMapping("/cancel")
    public ResponseEntity<String> cancelChat(
            @RequestBody CancelChatRequest request,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            UserTokenInfo tokenInfo = validateToken(token);
            String result = cozeProxyService.cancelChat(
                    request.getConversationId(), 
                    request.getChatId(), 
                    tokenInfo.cozeToken());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            log.error("Failed to cancel chat", e);
            return ResponseEntity.internalServerError().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 创建新会话
     */
    @PostMapping("/conversation/create")
    public ResponseEntity<String> createConversation(
            @RequestBody ChatRequest request,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            UserTokenInfo tokenInfo = validateToken(token);
            String result = cozeProxyService.createConversation(tokenInfo.cozeToken());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            log.error("Failed to create conversation", e);
            return ResponseEntity.internalServerError().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 获取消息历史
     */
    @GetMapping("/messages/{conversationId}")
    public ResponseEntity<String> getMessageHistory(
            @PathVariable String conversationId,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            UserTokenInfo tokenInfo = validateToken(token);
            String result = cozeProxyService.getMessageHistory(conversationId, tokenInfo.cozeToken());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            log.error("Failed to get message history", e);
            return ResponseEntity.internalServerError().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 文字转语音 (TTS)
     */
    @PostMapping("/tts")
    public ResponseEntity<byte[]> textToSpeech(
            @RequestBody java.util.Map<String, String> request,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            UserTokenInfo tokenInfo = validateToken(token);
            String text = request.get("text");
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            byte[] audioData = cozeProxyService.textToSpeech(text, tokenInfo.cozeToken());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .body(audioData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).build();
        } catch (IOException e) {
            log.error("Failed to generate speech", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 语音转文字 (ASR)
     */
    @PostMapping("/asr")
    public ResponseEntity<String> speechToText(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            UserTokenInfo tokenInfo = validateToken(token);
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("{\"error\":\"File is required\"}");
            }

            String jsonResult = cozeProxyService.speechToText(file, tokenInfo.cozeToken());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResult);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            log.error("Failed to transcribe speech", e);
            return ResponseEntity.internalServerError().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private SseEmitter sendErrorEmitter(String msg) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event().name("error").data("{\"msg\":\"" + msg + "\"}"));
            emitter.complete();
        } catch (IOException e) {
            // ignore
        }
        return emitter;
    }
}
