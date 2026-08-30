package com.muster.roster.dto;

import jakarta.validation.constraints.NotBlank;

public record PersonCreateRequest(
        @NotBlank(message = "员工编号不能为空") String employeeId,
        @NotBlank(message = "姓名不能为空") String name,
        @NotBlank(message = "手机号不能为空") String phone,
        @NotBlank(message = "部门不能为空") String department) {
}
