package com.volcano.chat.config;

import com.volcano.chat.websocket.CozeAsrWebSocketHandler;
import com.volcano.chat.websocket.CozeTtsWebSocketHandler;
import com.volcano.chat.websocket.WebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final CozeAsrWebSocketHandler asrHandler;
    private final CozeTtsWebSocketHandler ttsHandler;
    private final WebSocketAuthInterceptor authInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrHandler, "/ws/asr", "/api/chat/ws/asr")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");

        registry.addHandler(ttsHandler, "/ws/tts", "/api/chat/ws/tts")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean createWebSocketContainer() {
        org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean container = new org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(16 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(16 * 1024 * 1024);
        return container;
    }
}
