package com.muster.audit.dto;

import java.time.LocalDateTime;

public record OpLogView(Long id, String adminUsername, String action, String detail, LocalDateTime createdAt) {
}
