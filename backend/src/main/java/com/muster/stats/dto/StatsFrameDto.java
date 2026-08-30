package com.muster.stats.dto;

import java.util.List;

public record StatsFrameDto(long total, long registered, long notRegistered, long teamCount,
                            long pendingTeamCount, List<RecentEventDto> recentEvents) {
}
