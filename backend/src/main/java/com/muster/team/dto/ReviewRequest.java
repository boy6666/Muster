package com.muster.team.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewRequest(
        @NotBlank(message = "action 不能为空") String action,
        String reason) {
}
