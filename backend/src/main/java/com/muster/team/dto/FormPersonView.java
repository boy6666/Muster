package com.muster.team.dto;

public record FormPersonView(String employeeId, String name, String phone, String department,
                             Long teamId, boolean leader) {
}
