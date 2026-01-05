package com.volcano.chat.service;

import com.volcano.chat.config.CozeConfig;
import com.volcano.chat.coze.CozeAccessTokenProvider;
import com.volcano.chat.dto.ChatRequest;
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
    private final CozeAccessTokenProvider tokenProvider;
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
     */
    public SseEmitter sendMessage(ChatRequest request, String userUuid) {
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
                try {
                // 构建请求体
                Map<String, Object> body = new HashMap<>();
                body.put("workflow_id", cozeConfig.getWorkflowId());
                body.put("app_id", cozeConfig.getAppId());

                // 用户消息
                Map<String, Object> message = new HashMap<>();
                message.put("role", "user");
                message.put("content", request.getMessage());
                message.put("content_type", "text");
                body.put("additional_messages", new Object[] { message });

                // 参数（包含 user_uuid - 这个不会暴露给前端）
                Map<String, Object> params = new HashMap<>();
                params.put("user_uuid", userUuid);
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

                // 发送请求到 Coze API
                URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/workflows/chat");
                connection = (HttpURLConnection) url.openConnection();
                connectionRef.set(connection);
                applyTimeouts(connection);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                // 使用 userUuid（手机号）获取 token，实现会话隔离
                CozeAccessTokenProvider.TokenResponse tokenResp = tokenProvider.getAccessToken(userUuid);
                if (tokenResp.isNeedsPatConfirmation()) {
                    // 需要确认使用 PAT
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"msg\":\"" + tokenResp.getMessage() + "\",\"needs_pat_confirmation\":true}"));
                    emitter.complete();
                    return;
                }
                connection.setRequestProperty("Authorization", "Bearer " + tokenResp.getAccessToken());
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

                // 读取 SSE 响应并转发
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
                        }
                    }
                }

                emitter.complete();

            } catch (java.net.SocketTimeoutException e) {
                if (!stopRequested.get()) {
                    log.warn("Coze SSE read timeout ({}ms) for user {}...",
                            cozeReadTimeoutMs, userUuid.substring(0, Math.min(4, userUuid.length())));
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

    /**
     * 代理 TTS 请求到 Coze API
     */
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

    public byte[] textToSpeech(String text, String userUuid) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("input", text);
        body.put("voice_id", cozeConfig.getVoiceId());
        body.put("response_format", "mp3");

        String jsonBody = toJson(body);

        // 获取 token（带会话隔离）
        CozeAccessTokenProvider.TokenResponse tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.isNeedsPatConfirmation()) {
            throw new IOException("PAT confirmation required: " + tokenResp.getMessage());
        }

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/audio/speech");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + tokenResp.getAccessToken());
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
     * (Multipart/form-data 手动构建)
     */
    public String speechToText(org.springframework.web.multipart.MultipartFile file, String userUuid) throws IOException {
        String boundary = "---boundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";

        // 获取 token（带会话隔离）
        CozeAccessTokenProvider.TokenResponse tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.isNeedsPatConfirmation()) {
            throw new IOException("PAT confirmation required: " + tokenResp.getMessage());
        }

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/audio/transcriptions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Authorization", "Bearer " + tokenResp.getAccessToken());
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
    public String createConversation(String userUuid) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("bot_id", cozeConfig.getBotId());

        String jsonBody = toJson(body);

        // 获取 token（带会话隔离）
        CozeAccessTokenProvider.TokenResponse tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.isNeedsPatConfirmation()) {
            throw new IOException("PAT confirmation required: " + tokenResp.getMessage());
        }

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v1/conversation/create");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + tokenResp.getAccessToken());
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
    public String getMessageHistory(String conversationId, String userUuid) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("order", "asc");
        body.put("limit", 50);

        String jsonBody = toJson(body);

        // 获取 token（带会话隔离）
        CozeAccessTokenProvider.TokenResponse tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.isNeedsPatConfirmation()) {
            throw new IOException("PAT confirmation required: " + tokenResp.getMessage());
        }

        URL url = new URL(
                cozeConfig.getApiBaseUrl() + "/v1/conversation/message/list?conversation_id=" + conversationId);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + tokenResp.getAccessToken());
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
     * 取消进行中的对话（Chat Cancel API）
     */
    public String cancelChat(String conversationId, String chatId, String userUuid) throws IOException {
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

        CozeAccessTokenProvider.TokenResponse tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.isNeedsPatConfirmation()) {
            throw new IOException("PAT confirmation required: " + tokenResp.getMessage());
        }

        URL url = new URL(cozeConfig.getApiBaseUrl() + "/v3/chat/cancel");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyTimeouts(connection);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + tokenResp.getAccessToken());
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
     * 简单的 JSON 序列化（避免引入额外依赖）
     * 生产环境建议使用 Jackson
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
}
