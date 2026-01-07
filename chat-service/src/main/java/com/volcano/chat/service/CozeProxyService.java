package com.volcano.chat.service;

import com.volcano.chat.config.CozeConfig;
import com.volcano.chat.dto.ChatRequest;
import com.volcano.chat.entity.ChatLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coze API 代理服务
 * 负责将前端请求代理到 Coze API，隐藏敏感信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CozeProxyService {

    private final CozeConfig cozeConfig;
    private final ChatLogService chatLogService;
    private ExecutorService executor;

    @Value("${chat.sse.max-concurrent:300}")
    private int maxConcurrentSse;

    @Value("${chat.coze.connect-timeout-ms:5000}")
    private int cozeConnectTimeoutMs;

    @Value("${chat.coze.read-timeout-ms:60000}")
    private int cozeReadTimeoutMs;

    @Value("${chat.sse.emitter-timeout-ms:0}")
    private long emitterTimeoutMs;

    private Semaphore sseLimiter;
    private final AtomicInteger threadSeq = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        int max = maxConcurrentSse > 0 ? maxConcurrentSse : 300;
        maxConcurrentSse = max;
        sseLimiter = new Semaphore(max, true);

        int core = Math.min(50, max);
        executor = new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("coze-sse-" + threadSeq.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 代理发送消息请求到 Coze API
     * 使用 SSE 流式返回响应（流式）
     * 
     * 按照时序图实现两步写入：
     * 1. 发起请求前先插入问题记录 (Insert Q)
     * 2. 流结束后更新答案 (Update A)
     * 
     * @param request 聊天请求
     * @param userPhone 用户标识（手机号）
     * @param cozeToken 已缓存的 Coze OAuth Token
     */
    public SseEmitter sendMessage(ChatRequest request, String userPhone, String cozeToken) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs <= 0 ? 0L : emitterTimeoutMs);

        if (sseLimiter == null || !sseLimiter.tryAcquire()) {
            safeSendError(emitter, 429, "系统繁忙，请稍后重试");
            emitter.complete();
            return emitter;
        }

        AtomicBoolean stopRequested = new AtomicBoolean(false);
        AtomicBoolean permitReleased = new AtomicBoolean(false);
        AtomicReference<HttpURLConnection> connectionRef = new AtomicReference<>();

        Runnable releasePermit = () -> {
            if (permitReleased.compareAndSet(false, true)) {
                sseLimiter.release();
            }
        };

        Runnable cancelUpstream = () -> {
            stopRequested.set(true);
            HttpURLConnection conn = connectionRef.get();
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignore) {
                }
            }
        };

        emitter.onCompletion(() -> {
            cancelUpstream.run();
            releasePermit.run();
        });
        emitter.onTimeout(() -> {
            cancelUpstream.run();
            releasePermit.run();
        });
        emitter.onError(ex -> {
            cancelUpstream.run();
            releasePermit.run();
        });

        try {
            executor.execute(() -> {
                HttpURLConnection connection = null;
                LocalDateTime requestTime = LocalDateTime.now();
                StringBuilder aiAnswerBuilder = new StringBuilder();
                String userQuestion = request.getMessage();
                String sessionId = request.getConversationId();
                Long chatLogId = null;
                
                try {
                // ========== 步骤8: 先插入问题记录到数据库 (Insert Q) ==========
                chatLogId = insertQuestionLog(userPhone, sessionId, userQuestion, requestTime);
                
                // 构建请求体
                Map<String, Object> body = new HashMap<>();
                body.put("workflow_id", cozeConfig.getWorkflowId());
                body.put("app_id", cozeConfig.getAppId());

                // 用户消息
                Map<String, Object> message = new HashMap<>();
                message.put("role", "user");
                message.put("content", userQuestion);
                message.put("content_type", "text");
                body.put("additional_messages", new Object[] { message });

                // 参数（包含 user_uuid - 这个不会暴露给前端）
                Map<String, Object> params = new HashMap<>();
                params.put("user_uuid", userPhone);
                if (request.getParams() != null) {
                    params.putAll(request.getParams());
                }
                body.put("parameters", params);

                // 如果有会话 ID
                if (request.getConversationId() != null && !request.getConversationId().isEmpty()) {
                    body.put("conversation_id", request.getConversationId());
                }

                String jsonBody = toJson(body);
                log.debug("Coze request body: {}", jsonBody);

                // ========== 步骤9: 发送请求到 Coze API ==========
                URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/workflows/chat");
                connection = (HttpURLConnection) url.openConnection();
                connectionRef.set(connection);
                applyTimeouts(connection);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + cozeToken);
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setDoOutput(true);
                connection.setDoInput(true);

                // 写入请求体
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    String errorMsg = "Coze API error: " + responseCode;
                    log.error(errorMsg);
                    safeSendError(emitter, responseCode, errorMsg);
                    emitter.complete();
                    return;
                }

                // ========== 步骤10-11: 读取 SSE 响应并转发 ==========
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    String currentEvent = "";

                    while (!stopRequested.get() && (line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:") && !currentEvent.isEmpty()) {
                            String data = line.substring(5).trim();
                            // 转发事件到前端
                            emitter.send(SseEmitter.event()
                                    .name(currentEvent)
                                    .data(data));
                            
                            // 累积 AI 回答内容（根据事件类型提取）
                            extractAndAppendContent(currentEvent, data, aiAnswerBuilder);
                        }
                    }
                }

                // ========== 步骤13: 流结束后更新答案 (Update A) ==========
                updateAnswerLog(chatLogId, aiAnswerBuilder.toString(), LocalDateTime.now());

                emitter.complete();

            } catch (java.net.SocketTimeoutException e) {
                if (!stopRequested.get()) {
                    log.warn("Coze SSE read timeout ({}ms) for user {}...",
                            cozeReadTimeoutMs, userPhone.substring(0, Math.min(4, userPhone.length())));
                    safeSendError(emitter, 504, "上游流式响应超时（60秒无数据），请重试");
                    emitter.complete();
                }
            } catch (Exception e) {
                if (!stopRequested.get()) {
                    log.error("Error proxying to Coze API", e);
                    safeSendError(emitter, 500, e.getMessage() != null ? e.getMessage() : "Internal error");
                    emitter.complete();
                }
            } finally {
                connectionRef.set(null);
                if (connection != null) {
                    connection.disconnect();
                }
                releasePermit.run();
            }
            });
        } catch (RejectedExecutionException e) {
            cancelUpstream.run();
            releasePermit.run();
            safeSendError(emitter, 429, "系统繁忙，请稍后重试");
            emitter.complete();
        }

        return emitter;
    }

    private void applyTimeouts(HttpURLConnection connection) {
        if (cozeConnectTimeoutMs > 0) {
            connection.setConnectTimeout(cozeConnectTimeoutMs);
        }
        if (cozeReadTimeoutMs > 0) {
            connection.setReadTimeout(cozeReadTimeoutMs);
        }
    }

    private void safeSendError(SseEmitter emitter, int code, String msg) {
        try {
            Map<String, Object> err = new HashMap<>();
            err.put("code", code);
            err.put("msg", msg);
            emitter.send(SseEmitter.event().name("error").data(toJson(err)));
        } catch (IOException ignore) {
        }
    }

    /**
     * 代理 TTS 请求到 Coze API
     */
    public byte[] textToSpeech(String text, String cozeToken) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("input", text);
        body.put("voice_id", cozeConfig.getVoiceId());
        body.put("response_format", "mp3");

        String jsonBody = toJson(body);

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/audio/speech");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + cozeToken);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorResponse.append(line);
                }
                log.error("Coze TTS error: {} - {}", responseCode, errorResponse.toString());
            }
            throw new IOException("Failed to generate speech: " + responseCode);
        }

        try (java.io.InputStream is = connection.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 代理 ASR 请求到 Coze API
     */
    public String speechToText(org.springframework.web.multipart.MultipartFile file, String cozeToken) throws IOException {
        String boundary = "---boundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/audio/transcriptions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Authorization", "Bearer " + cozeToken);
        connection.setDoOutput(true);

        try (OutputStream outputStream = connection.getOutputStream();
                java.io.PrintWriter writer = new java.io.PrintWriter(
                        new java.io.OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

            // File part
            writer.append("--" + boundary).append(LINE_FEED);
            writer.append(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getOriginalFilename() + "\"")
                    .append(LINE_FEED);
            writer.append("Content-Type: " + (file.getContentType() != null ? file.getContentType() : "audio/wav"))
                    .append(LINE_FEED);
            writer.append(LINE_FEED);
            writer.flush();

            // File data
            try (java.io.InputStream inputStream = file.getInputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }

            writer.append(LINE_FEED);
            writer.append("--" + boundary + "--").append(LINE_FEED);
            writer.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorResponse.append(line);
                }
                log.error("Coze ASR error: {} - {}", responseCode, errorResponse.toString());
            }
            throw new IOException("Failed to transcribe audio: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 创建新会话
     */
    public String createConversation(String cozeToken) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("bot_id", cozeConfig.getBotId());

        String jsonBody = toJson(body);

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/conversation/create");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + cozeToken);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to create conversation: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 获取消息历史
     */
    public String getMessageHistory(String conversationId, String cozeToken) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("order", "asc");
        body.put("limit", 50);

        String jsonBody = toJson(body);

        URL url = new URL(
                cozeConfig.getApiBaseUrl() + "/v1/conversation/message/list?conversation_id=" + conversationId);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + cozeToken);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to get message history: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 取消进行中的对话
     */
    public String cancelChat(String conversationId, String chatId, String cozeToken) throws IOException {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId is required");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("conversation_id", conversationId);
        body.put("chat_id", chatId);
        String jsonBody = toJson(body);

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v3/chat/cancel");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + cozeToken);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode >= 200 && responseCode < 400 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            if (responseCode != 200) {
                throw new IOException("Failed to cancel chat: " + responseCode + " - " + response);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 简单的 JSON 序列化
     */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first)
                sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(valueToJson(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private String valueToJson(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + escapeJson((String) value) + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) value;
            return toJson(m);
        } else if (value instanceof Object[]) {
            StringBuilder sb = new StringBuilder("[");
            Object[] arr = (Object[]) value;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0)
                    sb.append(",");
                sb.append(valueToJson(arr[i]));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 从 SSE 事件数据中提取 AI 回答内容并累积
     */
    private void extractAndAppendContent(String eventType, String data, StringBuilder builder) {
        try {
            // 根据 Coze API 的事件类型提取内容
            // 常见事件: message, done, error 等
            if ("message".equals(eventType) || "Message".equals(eventType)) {
                // 尝试从 JSON 中提取 content 字段
                int contentStart = data.indexOf("\"content\":");
                if (contentStart != -1) {
                    int valueStart = data.indexOf("\"", contentStart + 10);
                    if (valueStart != -1) {
                        int valueEnd = findClosingQuote(data, valueStart + 1);
                        if (valueEnd != -1) {
                            String content = data.substring(valueStart + 1, valueEnd);
                            // 反转义
                            content = content.replace("\\n", "\n")
                                           .replace("\\r", "\r")
                                           .replace("\\t", "\t")
                                           .replace("\\\"", "\"")
                                           .replace("\\\\", "\\");
                            builder.append(content);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract content from SSE data: {}", e.getMessage());
        }
    }

    /**
     * 查找 JSON 字符串中的闭合引号位置（处理转义）
     */
    private int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++; // 跳过转义字符
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 步骤8: 插入问题记录到数据库 (Insert Q)
     * 在发起 Coze 请求前先记录用户提问
     * 
     * @return 记录ID，用于后续更新答案
     */
    private Long insertQuestionLog(String userPhone, String sessionId, String userQuestion, 
                                   LocalDateTime requestTime) {
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setUserId(userPhone);
            chatLog.setSessionId(sessionId);
            chatLog.setUserQuestion(userQuestion);
            chatLog.setAiAnswer(null); // 答案稍后更新
            chatLog.setRequestTime(requestTime);
            chatLog.setResponseTime(null); // 响应时间稍后更新
            chatLog.setDeleted(0);
            
            Long recordId = chatLogService.insert(chatLog);
            log.debug("Inserted question log (recordId: {}) for user: {}...", 
                     recordId, userPhone.substring(0, Math.min(4, userPhone.length())));
            return recordId;
        } catch (Exception e) {
            log.error("Failed to insert question log for user: {}", userPhone, e);
            return null;
        }
    }

    /**
     * 步骤13: 更新答案到数据库 (Update A)
     * 在 SSE 流结束后更新 AI 回答内容
     */
    private void updateAnswerLog(Long recordId, String aiAnswer, LocalDateTime responseTime) {
        if (recordId == null) {
            log.warn("Cannot update answer log: recordId is null");
            return;
        }
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setRecordId(recordId);
            chatLog.setAiAnswer(aiAnswer);
            chatLog.setResponseTime(responseTime);
            
            boolean success = chatLogService.updateById(chatLog);
            if (success) {
                log.debug("Updated answer log (recordId: {})", recordId);
            } else {
                log.warn("Failed to update answer log (recordId: {}): record not found or not modified", recordId);
            }
        } catch (Exception e) {
            log.error("Failed to update answer log (recordId: {})", recordId, e);
        }
    }

    /**
     * 保存聊天日志到数据库
     * @deprecated 使用 insertQuestionLog + updateAnswerLog 两步写入替代
     */
    @Deprecated
    private void saveChatLog(String userPhone, String sessionId, String userQuestion,
                            String aiAnswer, LocalDateTime requestTime, LocalDateTime responseTime) {
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setUserId(userPhone);
            chatLog.setSessionId(sessionId);
            chatLog.setUserQuestion(userQuestion);
            chatLog.setAiAnswer(aiAnswer);
            chatLog.setRequestTime(requestTime);
            chatLog.setResponseTime(responseTime);
            chatLog.setDeleted(0);
            
            chatLogService.insert(chatLog);
            log.debug("Chat log saved for user: {}...", userPhone.substring(0, Math.min(4, userPhone.length())));
        } catch (Exception e) {
            log.error("Failed to save chat log for user: {}", userPhone, e);
        }
    }
}
