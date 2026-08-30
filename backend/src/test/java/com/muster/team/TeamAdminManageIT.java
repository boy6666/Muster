package com.muster.team;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员建组：不走报名流程，创建即 CONFIRMED（管理员代为录入，无需审核）；
 * 删除不限状态；建组仍受活动窗口限制（改组/删组/审核不受限）。
 */
class TeamAdminManageIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @BeforeEach
    void setupActiveActivityWithRoster() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(List.of(
                List.of("E001", "张三", "13800000001", "计算机"),
                List.of("E002", "李四", "13800000002", "外语"),
                List.of("E003", "王五", "13800000003", "体育"),
                List.of("E004", "赵六", "13800000004", "数学"))));
    }

    private com.fasterxml.jackson.databind.JsonNode postTeam(String leader, List<String> members) throws Exception {
        var resp = postJson("/api/teams",
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody());
    }

    @Test
    void adminCreateTeamDirectlyConfirmed() throws Exception {
        var node = postTeam("E001", List.of("E001", "E002"));
        assertThat(node.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(node.path("submittedAt").isNull()).isFalse();
        assertThat(node.path("name").asText()).isEqualTo("组1");
        assertThat(node.path("members").size()).isEqualTo(2);
        assertThat(node.path("members").get(0).path("employeeId").asText()).isEqualTo("E001");
        assertThat(node.path("members").get(0).path("isLeader").asBoolean()).isTrue();
    }

    @Test
    void adminCreateConsumesNextNumber() throws Exception {
        // 扫码端先建了组1（草稿也算占用组号）
        postJson("/api/form/" + MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText() + "/teams",
                Map.of("leaderEmployeeId", "E003", "memberEmployeeIdList", List.of("E003")));

        var node = postTeam("E001", List.of("E001", "E002"));
        assertThat(node.path("name").asText()).isEqualTo("组2");
    }

    @Test
    void adminCreateConflictWhenMemberTaken() throws Exception {
        postJson("/api/form/" + MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText() + "/teams",
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E002")));

        var resp = postJson("/api/teams",
                Map.of("leaderEmployeeId", "E003", "memberEmployeeIdList", List.of("E003", "E002")));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("CONFLICT").contains("组1").contains("E002");
    }

    @Test
    void adminCreateRequiresActiveWindowButReviewStillWorks() throws Exception {
        // 先准备一个待审核组
        String formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
        var draft = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001")));
        long teamId = MAPPER.readTree(draft.getBody()).path("id").asLong();
        postJson("/api/form/" + formToken + "/teams/" + teamId + "/submit", Map.of("leaderPhone", "13800000001"));

        jdbc.update("UPDATE activity SET manually_ended = 1");

        var create = postJson("/api/teams",
                Map.of("leaderEmployeeId", "E003", "memberEmployeeIdList", List.of("E003")));
        assertThat(create.getStatusCode().value()).isEqualTo(409);
        assertThat(create.getBody()).contains("WINDOW_CLOSED");

        // 窗口结束后审核不受限
        var review = putJson("/api/teams/" + teamId + "/review", Map.of("action", "PASS"));
        assertThat(review.getStatusCode().value()).isEqualTo(200);
        var detail = getJson("/api/teams/" + teamId);
        assertThat(MAPPER.readTree(detail.getBody()).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void adminDeleteAnyStatus() throws Exception {
        long id = postTeam("E001", List.of("E001", "E002")).path("id").asLong();

        var del = deleteJson("/api/teams/" + id);
        assertThat(del.getStatusCode().value()).isEqualTo(200);

        var page = getJson("/api/teams?page=1&size=20");
        assertThat(page.getBody()).contains("\"total\":0");
    }

    @Test
    void adminCreateMissingLeaderRejected() {
        var resp = postJson("/api/teams",
                Map.of("leaderEmployeeId", " ", "memberEmployeeIdList", List.of("E001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }
}
