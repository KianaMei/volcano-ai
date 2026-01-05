package com.volcano.chat.websocket;

import com.volcano.chat.coze.CozeAccessTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASR WebSocket 代理 Handler
 *
 * 设计原则：和 TTS Handler 保持一致的简单结构
 * - 只维护一个 Map（cozeSessions）
 * - 不做消息缓冲，前端需等待 connection.ready 事件后再发送数据
 * - 前端在 ready 之前发送的消息会被丢弃
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CozeAsrWebSocketHandler extends TextWebSocketHandler {

    private final CozeAccessTokenProvider tokenProvider;

    // Coze session per user - 唯一的状态源
    private final Map<String, WebSocketSession> cozeSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession userSession) throws Exception {
        String sessionId = userSession.getId();
        log.info("ASR connection established: {}", sessionId);

        String userUuid = (String) userSession.getAttributes().get("userUuid");
        if (userUuid == null) {
            log.error("User UUID not found in session attributes");
            userSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        var tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.getAccessToken() == null) {
            log.error("Failed to get Coze access token for ASR");
            userSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        WebSocketClient client = new StandardWebSocketClient();
        String cozeUrl = "wss://ws.coze.cn/v1/audio/transcriptions?authorization=Bearer " + tokenResp.getAccessToken();

        client.doHandshake(new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession cozeSession) throws Exception {
                log.info("Coze ASR connection established for user: {}", sessionId);
                cozeSessions.put(sessionId, cozeSession);

                // 通知前端可以开始发送数据了
                if (userSession.isOpen()) {
                    userSession.sendMessage(new TextMessage("{\"event_type\":\"connection.ready\",\"data\":{}}"));
                }
            }

            @Override
            public void handleMessage(WebSocketSession cozeSession, WebSocketMessage<?> message) throws Exception {
                if (userSession.isOpen()) {
                    userSession.sendMessage(message);
                }
            }

            @Override
            public void handleTransportError(WebSocketSession cozeSession, Throwable exception) throws Exception {
                log.error("Coze ASR transport error for user: {}", sessionId, exception);
                cozeSessions.remove(sessionId);
                if (userSession.isOpen()) {
                    userSession.sendMessage(
                            new TextMessage("{\"event_type\":\"error\",\"data\":{\"msg\":\"Coze connection error\"}}"));
                    userSession.close(CloseStatus.SERVER_ERROR);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession cozeSession, CloseStatus closeStatus) throws Exception {
                log.info("Coze ASR connection closed for user: {}, status: {}", sessionId, closeStatus);
                cozeSessions.remove(sessionId);
                if (userSession.isOpen()) {
                    userSession.close(closeStatus);
                }
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        }, cozeUrl);
    }

    @Override
    protected void handleTextMessage(WebSocketSession userSession, TextMessage message) throws Exception {
        forwardToCoze(userSession, message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession userSession, BinaryMessage message) {
        try {
            forwardToCoze(userSession, message);
        } catch (IOException e) {
            log.error("Error forwarding binary message", e);
        }
    }

    /**
     * 转发消息到 Coze
     * 如果 Coze 连接未就绪，消息会被丢弃（前端应等待 connection.ready）
     */
    private void forwardToCoze(WebSocketSession userSession, WebSocketMessage<?> message) throws IOException {
        WebSocketSession cozeSession = cozeSessions.get(userSession.getId());
        if (cozeSession != null && cozeSession.isOpen()) {
            cozeSession.sendMessage(message);
        } else {
            log.debug("Coze session not ready, message dropped for user: {}", userSession.getId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession userSession, Throwable exception) throws Exception {
        log.error("User ASR transport error", exception);
        cleanup(userSession.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession userSession, CloseStatus status) throws Exception {
        log.info("User ASR connection closed: {}", userSession.getId());
        cleanup(userSession.getId());
    }

    private void cleanup(String sessionId) {
        WebSocketSession cozeSession = cozeSessions.remove(sessionId);
        if (cozeSession != null && cozeSession.isOpen()) {
            try {
                cozeSession.close();
            } catch (IOException e) {
                log.error("Error closing Coze session", e);
            }
        }
    }
}
