package com.muster.team.dto;

import java.time.LocalDateTime;

public record FormInfo(String name, LocalDateTime startTime, LocalDateTime endTime,
                       Integer groupSizeLimit, String windowStatus) {
}
