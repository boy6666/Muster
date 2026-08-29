package com.muster.team.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TeamDetail(Long id, String name, String status, String rejectReason, String capToken,
                         boolean overLimit, LocalDateTime submittedAt, List<TeamMemberView> members) {
}
