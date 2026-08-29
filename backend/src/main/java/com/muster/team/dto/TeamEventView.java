package com.muster.team.dto;

import java.time.LocalDateTime;

public record TeamEventView(Long id, String type, String detail, LocalDateTime createdAt) {
}
