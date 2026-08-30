package com.muster.team;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组名生成重试语义：候选名必须随尝试次数推进。
 * REPEATABLE READ 下重试读到的 count 是同一快照，若固定用 count+1，
 * 并发撞名时三次尝试会算出同一个名字，全部撞 uk_activity_name 唯一键。
 * 用例：存量 组1..组5，另有一行占用了 组7（并发提交者已越过 组6），
 * 新提交 count=6、首选候选 组7 撞键，重试必须推进到 组8。
 */
class TeamNameRetryIT extends IntegrationTestBase {

    private String formToken;

    @BeforeEach
    void setupActiveActivityWithRosterAndSquatters() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(List.of(List.of("E001", "张三", "13800000001", "计算机"))));
        Long activityId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(getJson("/api/activity").getBody()).path("id").asLong();
        formToken = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
        // 直接落库模拟：组1..组5 + 组7 被并发占走（跳过 组6，使首选候选 组7 撞键）
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO team(activity_id, name, status) VALUES(?, ?, 'CONFIRMED')",
                    activityId, "组" + i);
        }
        jdbc.update("INSERT INTO team(activity_id, name, status) VALUES(?, '组7', 'CONFIRMED')", activityId);
    }

    @Test
    void retryAdvancesNamePastSquattedCandidate() throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(resp.getBody()).path("name").asText()).isEqualTo("组8");
    }
}
