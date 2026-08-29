package com.muster.team.dto;

import java.time.LocalDateTime;

public record TeamAdminResponse(Long id, String name, String status, int size, boolean overLimit,
                                String rejectReason, LocalDateTime submittedAt) {
}
