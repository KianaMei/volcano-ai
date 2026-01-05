package com.volcano.chat.websocket;

import com.volcano.chat.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final SessionService sessionService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {

        // 解析 URL 参数中的 token
        URI uri = request.getURI();
        String query = uri.getQuery();
        String token = null;

        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] idx = pair.split("=");
                if (idx.length == 2 && "token".equals(idx[0])) {
                    token = idx[1];
                    break;
                }
            }
        }

        if (token == null) {
            log.warn("WebSocket handshake failed: Missing token");
            return false;
        }

        String userUuid = sessionService.getUserUuid(token);
        if (userUuid == null) {
            log.warn("WebSocket handshake failed: Invalid token");
            return false;
        }

        // 将 userUuid 存入 session attributes，供 Handler 使用
        attributes.put("userUuid", userUuid);

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
