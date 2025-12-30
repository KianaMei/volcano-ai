package com.volcano.chat.service.config;

import com.volcano.chat.service.websocket.CozeAsrWebSocketHandler;
import com.volcano.chat.service.websocket.CozeTtsWebSocketHandler;
import com.volcano.chat.service.websocket.WebSocketAuthInterceptor;
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
        container.setMaxTextMessageBufferSize(512 * 1024); // 512KB
        container.setMaxBinaryMessageBufferSize(512 * 1024); // 512KB
        return container;
    }
}
