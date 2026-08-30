package com.muster.roster.dto;

import com.muster.roster.Person;

public record PersonResponse(Long id, String employeeId, String name, String phone, String department,
                             Long teamId, String teamName, String leaderName,
                             boolean isLeader, boolean participated) {

    public static PersonResponse from(Person p) {
        return new PersonResponse(p.getId(), p.getEmployeeId(), p.getName(), p.getPhone(), p.getDepartment(),
                null, null, null, false, false);
    }
}
