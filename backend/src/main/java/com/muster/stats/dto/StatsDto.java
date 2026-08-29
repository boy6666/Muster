package com.muster.stats.dto;

public record StatsDto(long total, long joined, long notJoined, long teamCount, long pendingTeamCount) {
}
