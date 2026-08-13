package io.vpp.platformapi.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import io.vpp.platformapi.config.RealtimeProperties;

@Configuration
@EnableWebSocket
public class RealtimeWebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final RealtimeHandshakeInterceptor handshake;
    private final RealtimeProperties properties;

    public RealtimeWebSocketConfig(RealtimeWebSocketHandler handler,
            RealtimeHandshakeInterceptor handshake, RealtimeProperties properties) {
        this.handler = handler;
        this.handshake = handshake;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/v1")
                .addInterceptors(handshake)
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
