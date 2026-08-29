package com.muster.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muster.stats.dto.StatsDto;
import com.muster.team.StatsChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class StatsWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final StatsService statsService;
    private final ObjectMapper objectMapper;

    public StatsWebSocketHandler(StatsService statsService, ObjectMapper objectMapper) {
        this.statsService = statsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        send(session, toJson(statsService.current()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
    }

    @EventListener
    public void onStatsChanged(StatsChangedEvent event) {
        String payload = toJson(statsService.current());
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            sessions.remove(session);
        }
    }

    private String toJson(StatsDto stats) {
        try {
            return objectMapper.writeValueAsString(stats);
        } catch (IOException e) {
            throw new IllegalStateException("序列化统计失败", e);
        }
    }
}
