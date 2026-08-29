package com.muster.activity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record ActivityCreateRequest(
        @NotBlank(message = "活动名称不能为空") String name,
        @NotNull(message = "开始时间不能为空") LocalDateTime startTime,
        @NotNull(message = "结束时间不能为空") LocalDateTime endTime,
        @NotNull(message = "每组人数上限不能为空") @Min(value = 1, message = "每组人数上限至少为 1") Integer groupSizeLimit) {
}
