package com.muster.team.dto;

/**
 * 首次提交/换机验证的组长手机号。可整体缺省（重提交凭 cap 时 body 为空），
 * 是否必填由 service 按 submittedAt 判断，故不加 @NotBlank。
 */
public record LeaderVerifyRequest(String leaderPhone) {
}
