package com.muster.roster.dto;

import com.muster.roster.Person;

public record PersonResponse(Long id, String name, String phone, String department) {

    public static PersonResponse from(Person p) {
        return new PersonResponse(p.getId(), p.getName(), p.getPhone(), p.getDepartment());
    }
}
