package com.muster.activity.dto;

import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;

public record ActivityUpdateRequest(
        String name,
        LocalDateTime startTime,
        LocalDateTime endTime,
        @Min(value = 1, message = "每组人数上限至少为 1") Integer groupSizeLimit) {
}
