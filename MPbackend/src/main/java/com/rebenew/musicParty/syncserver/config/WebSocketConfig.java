package com.rebenew.musicParty.syncserver.config;

import com.rebenew.musicParty.syncserver.websocket.SyncWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SyncWebSocketHandler syncWebSocketHandler;

    public WebSocketConfig(SyncWebSocketHandler syncWebSocketHandler) {
        this.syncWebSocketHandler = syncWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(syncWebSocketHandler, "/ws/music-sync")
                .setAllowedOriginPatterns("*");
    }
}