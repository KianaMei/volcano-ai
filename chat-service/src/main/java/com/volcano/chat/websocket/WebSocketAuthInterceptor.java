package com.volcano.chat.websocket;

import com.volcano.chat.dto.UserTokenInfo;
import com.volcano.chat.service.UserTokenService;
import com.volcano.chat.util.JwtUtil;
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

    private final UserTokenService userTokenService;

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

        // 验证 JWT 签名和过期时间
        JwtUtil.JwtValidationResult jwtResult = JwtUtil.validateToken(token);
        if (!jwtResult.valid()) {
            log.warn("WebSocket handshake failed: {}", jwtResult.errorMessage());
            return false;
        }

        // 从 Redis 获取用户信息
        UserTokenInfo tokenInfo = userTokenService.getUserTokenInfo(token);
        if (tokenInfo == null) {
            log.warn("WebSocket handshake failed: Invalid or expired token");
            return false;
        }

        // 将用户信息存入 session attributes，供 Handler 使用
        attributes.put("userUuid", tokenInfo.phone());
        attributes.put("cozeToken", tokenInfo.cozeToken());

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
