package com.muster.team;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 花名册人员被删除后，组详情不得因成员行残留而 500：
 * - 有外键的库：删除人员级联清掉 team_member，成员自然消失；
 * - 无外键的历史库：detail 需空安全兜底（占位显示），保证 stats/导出/详情可继续工作。
 */
class TeamOrphanMemberIT extends IntegrationTestBase {

    private String formToken;

    @BeforeEach
    void setupActiveActivityWithRoster() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(List.of(
                List.of("张三", "13800000001", "计算机"),
                List.of("李四", "13800000002", "外语"))));
        formToken = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    @Test
    void teamDetailSurvivesDeletedRosterPerson() throws Exception {
        var submitted = postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000001", "13800000002")));
        assertThat(submitted.getStatusCode().value()).isEqualTo(200);
        long teamId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(submitted.getBody()).path("id").asLong();

        // 模拟竞态残留：绕过 roster API 直接删 person 行（历史库无外键时留下孤儿成员行）
        jdbc.update("DELETE FROM person WHERE phone = '13800000002'");

        var resp = getJson("/api/teams/" + teamId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).doesNotContain("13800000002");
    }
}
