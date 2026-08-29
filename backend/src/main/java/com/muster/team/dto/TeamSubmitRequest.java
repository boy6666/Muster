package com.muster.team.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record TeamSubmitRequest(@NotNull(message = "memberPhoneList 不能为空") List<String> memberPhoneList) {
}
