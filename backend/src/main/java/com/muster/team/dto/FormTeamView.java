package com.muster.team.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报名端"我的组"视图：不含 capToken（成员/组长都能看，能力令牌只给组长本人）。
 */
public record FormTeamView(Long id, String name, String status, String rejectReason, boolean overLimit,
                           LocalDateTime submittedAt, boolean isLeader, List<TeamMemberView> members) {
}
