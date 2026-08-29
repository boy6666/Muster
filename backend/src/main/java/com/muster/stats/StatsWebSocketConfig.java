package com.muster.stats;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class StatsWebSocketConfig implements WebSocketConfigurer {

    private final StatsWebSocketHandler statsWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    public StatsWebSocketConfig(StatsWebSocketHandler statsWebSocketHandler,
                                JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.statsWebSocketHandler = statsWebSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(statsWebSocketHandler, "/ws/stats")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
