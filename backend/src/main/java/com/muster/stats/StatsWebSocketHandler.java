package com.muster.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.stats.dto.RecentEventDto;
import com.muster.stats.dto.StatsDto;
import com.muster.stats.dto.StatsFrameDto;
import com.muster.team.StatsChangedEvent;
import com.muster.team.Team;
import com.muster.team.TeamEvent;
import com.muster.team.TeamEventMapper;
import com.muster.team.TeamMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

@Component
public class StatsWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final StatsService statsService;
    private final ActivityService activityService;
    private final TeamMapper teamMapper;
    private final TeamEventMapper teamEventMapper;
    private final ObjectMapper objectMapper;

    public StatsWebSocketHandler(StatsService statsService, ActivityService activityService,
                                 TeamMapper teamMapper, TeamEventMapper teamEventMapper,
                                 ObjectMapper objectMapper) {
        this.statsService = statsService;
        this.activityService = activityService;
        this.teamMapper = teamMapper;
        this.teamEventMapper = teamEventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        send(session, toJson(buildFrame()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
    }

    @EventListener
    public void onStatsChanged(StatsChangedEvent event) {
        String payload = toJson(buildFrame());
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    /** 统计帧：四卡数字 + 当前活动最近 20 条组事件（首帧与推送帧同构）。 */
    private StatsFrameDto buildFrame() {
        StatsDto stats = statsService.current();
        return new StatsFrameDto(stats.total(), stats.registered(), stats.notRegistered(),
                stats.teamCount(), stats.pendingTeamCount(), recentEvents());
    }

    /** 组名批量映射；组已删除（事件残留）显示「已删除组」。 */
    private List<RecentEventDto> recentEvents() {
        Activity activity = activityService.current();
        if (activity == null) {
            return List.of();
        }
        List<TeamEvent> events = teamEventMapper.selectList(new LambdaQueryWrapper<TeamEvent>()
                .eq(TeamEvent::getActivityId, activity.getId())
                .orderByDesc(TeamEvent::getId)
                .last("LIMIT 20"));
        if (events.isEmpty()) {
            return List.of();
        }
        List<Long> teamIds = events.stream().map(TeamEvent::getTeamId).distinct().toList();
        Map<Long, String> teamNames = teamMapper.selectBatchIds(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        return events.stream()
                .map(e -> new RecentEventDto(e.getTeamId(),
                        teamNames.getOrDefault(e.getTeamId(), "已删除组"),
                        e.getType(), e.getDetail(), e.getCreatedAt()))
                .toList();
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

    private String toJson(StatsFrameDto frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (IOException e) {
            throw new IllegalStateException("序列化统计帧失败", e);
        }
    }
}
