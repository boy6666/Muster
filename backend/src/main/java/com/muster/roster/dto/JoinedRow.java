package com.muster.roster.dto;

/** 已参加导出行：仅含通过审核组的成员。isLeader 用 0/1 承接 SQL 布尔。 */
public record JoinedRow(String employeeId, String name, String phone, String department,
                        String teamName, Integer isLeader) {
}
