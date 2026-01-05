package com.volcano.chat.controller;

import com.volcano.chat.dto.ChatRequest;
import com.volcano.chat.dto.CancelChatRequest;
import com.volcano.chat.service.CozeProxyService;
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
import com.volcano.chat.service.SessionService;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ChatController {

    private final CozeProxyService cozeProxyService;
    private final SessionService sessionService;

    /**
     * Helper method to validate token
     */
    private String validateToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Missing X-Chat-Token header");
        }
        String userUuid = sessionService.getUserUuid(token);
        if (userUuid == null) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        return userUuid;
    }

    /**
     * 发送消息（SSE 流式响应）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @RequestBody ChatRequest request,
            @RequestHeader("X-Chat-Token") String token) {
        // 1. 验证 Token 并获取手机号
        String userUuid;
        try {
            userUuid = validateToken(token);
        } catch (IllegalArgumentException e) {
            return sendErrorEmitter(e.getMessage());
        }

        log.info("Received chat request for user: {}...", userUuid.substring(0, Math.min(8, userUuid.length())));

        // 2. 调用代理服务 (传入安全的 userUuid)
        return cozeProxyService.sendMessage(request, userUuid);
    }

    /**
     * 取消进行中的对话
     */
    @PostMapping("/cancel")
    public ResponseEntity<String> cancelChat(
            @RequestBody CancelChatRequest request,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            String userUuid = validateToken(token);
            String result = cozeProxyService.cancelChat(request.getConversationId(), request.getChatId(), userUuid);
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
            String userUuid = validateToken(token);
            String result = cozeProxyService.createConversation(userUuid);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (IOException e) {
            log.error("Failed to create conversation", e);
            return ResponseEntity.internalServerError().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ... (getMessageHistory also needs update similarly, relying on userUuid
    // implicitly or just token validation if no userUuid needed for history
    // retrieval logic in Coze, but best to validate)

    /**
     * 获取消息历史
     */
    @GetMapping("/messages/{conversationId}")
    public ResponseEntity<String> getMessageHistory(
            @PathVariable String conversationId,
            @RequestHeader("X-Chat-Token") String token) {
        try {
            // 验证 Token 并获取 userUuid
            String userUuid = validateToken(token);

            String result = cozeProxyService.getMessageHistory(conversationId, userUuid);
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
            String userUuid = validateToken(token);
            String text = request.get("text");
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            byte[] audioData = cozeProxyService.textToSpeech(text, userUuid);
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
            String userUuid = validateToken(token);
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("{\"error\":\"File is required\"}");
            }

            String jsonResult = cozeProxyService.speechToText(file, userUuid);
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
