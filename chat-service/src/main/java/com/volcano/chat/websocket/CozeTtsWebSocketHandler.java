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

@Slf4j
@Component
@RequiredArgsConstructor
public class CozeTtsWebSocketHandler extends TextWebSocketHandler {

    private final CozeAccessTokenProvider tokenProvider;
    private final Map<String, WebSocketSession> cozeSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession userSession) throws Exception {
        log.info("TTS connection established: {}", userSession.getId());

        // 从 session attributes 获取 userUuid（由 WebSocketAuthInterceptor 设置）
        String userUuid = (String) userSession.getAttributes().get("userUuid");
        if (userUuid == null) {
            log.error("User UUID not found in session attributes");
            userSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        var tokenResp = tokenProvider.getAccessToken(userUuid);
        if (tokenResp.getAccessToken() == null) {
            log.error("Failed to get Coze access token for TTS");
            userSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        WebSocketClient client = new StandardWebSocketClient();
        String cozeUrl = "wss://ws.coze.cn/v1/audio/speech?authorization=Bearer " + tokenResp.getAccessToken();

        client.doHandshake(new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession cozeSession) throws Exception {
                log.info("Coze TTS connection established for user: {}", userSession.getId());
                cozeSessions.put(userSession.getId(), cozeSession);
            }

            @Override
            public void handleMessage(WebSocketSession cozeSession, WebSocketMessage<?> message) throws Exception {
                if (userSession.isOpen()) {
                    userSession.sendMessage(message);
                }
            }

            @Override
            public void handleTransportError(WebSocketSession cozeSession, Throwable exception) throws Exception {
                log.error("Coze TTS transport error", exception);
                if (userSession.isOpen()) {
                    userSession.close(CloseStatus.SERVER_ERROR);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession cozeSession, CloseStatus closeStatus) throws Exception {
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

    private void forwardToCoze(WebSocketSession userSession, WebSocketMessage<?> message) throws IOException {
        WebSocketSession cozeSession = cozeSessions.get(userSession.getId());
        if (cozeSession != null && cozeSession.isOpen()) {
            cozeSession.sendMessage(message);
        } else {
            log.warn("Coze session not ready for user: {}", userSession.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession userSession, CloseStatus status) throws Exception {
        WebSocketSession cozeSession = cozeSessions.remove(userSession.getId());
        if (cozeSession != null && cozeSession.isOpen()) {
            cozeSession.close();
        }
    }
}
