package com.muster.team.dto;

import java.util.List;

/**
 * 建组/保存/管理员改组统一请求。leaderEmployeeId 校验在 service 侧：
 * 报名端与管理员建组必填，管理员改组可省略（沿用原组长或取首位成员）。
 */
public record TeamMemberRequest(String leaderEmployeeId, List<String> memberEmployeeIdList) {
}
