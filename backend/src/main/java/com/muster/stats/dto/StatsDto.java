package com.muster.stats.dto;

public record StatsDto(long total, long registered, long notRegistered, long teamCount, long pendingTeamCount) {
}
