package com.muster.stats.dto;

import java.time.LocalDateTime;

public record RecentEventDto(long teamId, String teamName, String type, String detail, LocalDateTime createdAt) {
}
