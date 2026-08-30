package com.muster.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamReviewFlowIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private String formToken;
    private long team1;
    private long team2;
    private long team3;
    private String cap1;
    private String cap2;
    private String cap3;

    @BeforeEach
    void setup() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(List.of(
                List.of("E001", "张三", "13800000001", "计算机"),
                List.of("E002", "李四", "13800000002", "外语"),
                List.of("E003", "王五", "13800000003", "体育"),
                List.of("E004", "赵六", "13800000004", "数学"),
                List.of("E005", "钱七", "13800000005", "物理"),
                List.of("E006", "孙八", "13800000006", "化学"),
                List.of("E007", "周九", "13800000007", "生物"),
                List.of("E008", "吴十", "13800000008", "历史"))));
        formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();

        // team1/team2 已提交待审核，team3 仍是草稿
        var first = createDraft("E001", List.of("E001", "E002", "E003"));
        team1 = first.path("id").asLong();
        cap1 = first.path("capToken").asText();
        postJson("/api/form/" + formToken + "/teams/" + team1 + "/submit", Map.of("leaderPhone", "13800000001"));

        var second = createDraft("E006", List.of("E006", "E007"));
        team2 = second.path("id").asLong();
        cap2 = second.path("capToken").asText();
        postJson("/api/form/" + formToken + "/teams/" + team2 + "/submit", Map.of("leaderPhone", "13800000006"));

        var third = createDraft("E008", List.of("E008"));
        team3 = third.path("id").asLong();
        cap3 = third.path("capToken").asText();
    }

    private JsonNode createDraft(String leader, List<String> members) throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody());
    }

    private JsonNode review(long teamId, String action, String reason) throws Exception {
        var resp = putJson("/api/teams/" + teamId + "/review",
                reason == null ? Map.of("action", action) : Map.of("action", action, "reason", reason));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody());
    }

    /** 组长保存：仅换成员，不改状态。 */
    private JsonNode save(long teamId, String cap, String leader, List<String> members) throws Exception {
        var resp = putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap,
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody());
    }

    @Test
    void rejectWithoutReason400() {
        var resp = putJson("/api/teams/" + team1 + "/review", Map.of("action", "REJECT"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void rejectWithReasonMarksRejected() throws Exception {
        review(team1, "REJECT", "名单有误");

        var detail = getJson("/api/teams/" + team1);
        assertThat(detail.getBody()).contains("\"status\":\"REJECTED\"").contains("名单有误");
    }

    @Test
    void passMarksConfirmed() throws Exception {
        review(team1, "PASS", null);

        var detail = getJson("/api/teams/" + team1);
        assertThat(detail.getBody()).contains("\"status\":\"CONFIRMED\"");
    }

    @Test
    void saveAfterRejectionKeepsRejectedAndReason() throws Exception {
        review(team1, "REJECT", "名单有误");

        var saved = save(team1, cap1, "E001", List.of("E001", "E002", "E003", "E004"));
        assertThat(saved.path("status").asText()).isEqualTo("REJECTED");
        assertThat(saved.path("rejectReason").asText()).isEqualTo("名单有误");
        assertThat(saved.path("members").size()).isEqualTo(4);
        assertThat(saved.get("members").toString()).contains("E004");
    }

    @Test
    void resubmitAfterRejectionClearsReason() throws Exception {
        review(team1, "REJECT", "名单有误");

        var resp = postJson("/api/form/" + formToken + "/teams/" + team1 + "/submit?cap=" + cap1, Map.of());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var node = MAPPER.readTree(resp.getBody());
        assertThat(node.path("status").asText()).isEqualTo("PENDING");
        assertThat(node.path("rejectReason").isNull()).isTrue();
    }

    @Test
    void saveBlockedWhilePending() throws Exception {
        var resp = putJson("/api/form/" + formToken + "/teams/" + team1 + "?cap=" + cap1,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("审核中");
    }

    @Test
    void saveBlockedAfterConfirmed() throws Exception {
        review(team1, "PASS", null);

        var resp = putJson("/api/form/" + formToken + "/teams/" + team1 + "?cap=" + cap1,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("已通过审核");
    }

    @Test
    void adminEditMarksConfirmed() {
        // 管理员改组：组长可省略（E006 仍在新名单内，沿用原组长）；E004 空闲可加入
        var resp = putJson("/api/teams/" + team2 + "/members",
                Map.of("memberEmployeeIdList", List.of("E006", "E007", "E004")));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"status\":\"CONFIRMED\"");
    }

    @Test
    void adminCanEditPendingTeam() throws Exception {
        var resp = putJson("/api/teams/" + team1 + "/members",
                Map.of("memberEmployeeIdList", List.of("E002", "E003")));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"status\":\"CONFIRMED\"");
        // 原组长 E001 不在新名单 → 组长回落为首位成员 E002
        assertThat(MAPPER.readTree(resp.getBody()).path("members").get(0).path("isLeader").asBoolean()).isTrue();
    }

    @Test
    void editsBlockedAfterWindowEndsButReviewStillWorks() {
        jdbc.update("UPDATE activity SET manually_ended = 1");

        var leaderBlocked = putJson("/api/form/" + formToken + "/teams/" + team3 + "?cap=" + cap3,
                Map.of("leaderEmployeeId", "E008", "memberEmployeeIdList", List.of("E008")));
        assertThat(leaderBlocked.getStatusCode().value()).isEqualTo(409);
        assertThat(leaderBlocked.getBody()).contains("WINDOW_CLOSED");

        var adminBlocked = putJson("/api/teams/" + team1 + "/members",
                Map.of("memberEmployeeIdList", List.of("E001")));
        assertThat(adminBlocked.getStatusCode().value()).isEqualTo(409);

        var review = putJson("/api/teams/" + team1 + "/review", Map.of("action", "PASS"));
        assertThat(review.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void teamListFiltersByStatusAndFlagsOverLimit() throws Exception {
        jdbc.update("UPDATE activity SET group_size_limit = 1");
        review(team2, "REJECT", "重交");

        var rejected = getJson("/api/teams?status=REJECTED&page=1&size=20");
        assertThat(rejected.getBody()).contains("\"total\":1").contains("\"name\":\"组2\"");

        var drafts = getJson("/api/teams?status=DRAFT&page=1&size=20");
        assertThat(drafts.getStatusCode().value()).isEqualTo(200);
        assertThat(drafts.getBody()).contains("\"total\":1").contains("\"name\":\"组3\"");

        var all = getJson("/api/teams?page=1&size=20");
        assertThat(all.getBody()).contains("\"overLimit\":true");
    }

    @Test
    void teamListShowsLeaderName() {
        var page = getJson("/api/teams?page=1&size=20");
        assertThat(page.getBody()).contains("\"leaderName\":\"张三\"").contains("\"leaderName\":\"孙八\"");
    }

    @Test
    void movingPersonOutOfTeamReducesMembershipCount() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM team_member", Integer.class);
        assertThat(before).isEqualTo(6);

        // 驳回解锁后：组长转让给 E002 并把 E001 移出
        review(team1, "REJECT", "名单有误");
        save(team1, cap1, "E002", List.of("E002", "E003"));

        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM team_member", Integer.class);
        assertThat(after).isEqualTo(5);
    }

    @Test
    void saveConflictWhenMemberTaken() throws Exception {
        review(team2, "REJECT", "名单有误");

        var resp = putJson("/api/form/" + formToken + "/teams/" + team2 + "?cap=" + cap2,
                Map.of("leaderEmployeeId", "E006", "memberEmployeeIdList", List.of("E006", "E001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("CONFLICT").contains("组1").contains("E001");
    }

    @Test
    void reviewOfUnknownTeamReturns404() {
        var resp = putJson("/api/teams/999/review", Map.of("action", "PASS"));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
}
