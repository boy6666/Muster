package com.muster.team.dto;

public record TeamMemberView(String employeeId, String name, String phone, String department, boolean isLeader) {
}
