package com.muster.team;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamReviewFlowIT extends IntegrationTestBase {

    private String formToken;
    private Long team1;
    private Long team2;
    private String cap1;
    private String cap2;

    @BeforeEach
    void setup() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(List.of(
                List.of("张三", "13800000001", "计算机"),
                List.of("李四", "13800000002", "外语"),
                List.of("王五", "13800000003", "体育"),
                List.of("赵六", "13800000004", "数学"),
                List.of("钱七", "13800000005", "物理"),
                List.of("孙八", "13800000006", "化学"),
                List.of("周九", "13800000007", "生物"),
                List.of("吴十", "13800000008", "历史"))));
        formToken = json(getJson("/api/activity").getBody()).path("qrToken").asText();

        var first = postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000001", "13800000002", "13800000003")));
        team1 = json(first.getBody()).path("id").asLong();
        cap1 = json(first.getBody()).path("capToken").asText();
        var second = postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000006", "13800000007")));
        team2 = json(second.getBody()).path("id").asLong();
        cap2 = json(second.getBody()).path("capToken").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode json(String body) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
    }

    private ResponseEntity<String> leaderEdit(Long teamId, String cap, List<String> phones) {
        return putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap,
                Map.of("memberPhoneList", phones));
    }

    @Test
    void leaderEditReplacesMembersAndKeepsPending() {
        var resp = leaderEdit(team1, cap1, List.of("13800000003", "13800000004", "13800000005"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"status\":\"PENDING\"")
                .contains("13800000004")
                .contains("13800000005")
                .doesNotContain("13800000001");

        var other = getJson("/api/form/" + formToken + "/teams/" + team2 + "?cap=" + cap2);
        assertThat(other.getBody()).contains("13800000006").contains("13800000007");
    }

    @Test
    void leaderEditWithPersonInOtherTeamConflicts() {
        var resp = leaderEdit(team1, cap1, List.of("13800000001", "13800000006"));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("CONFLICT").contains("组2");
    }

    @Test
    void leaderEditKeepingOwnMembersSucceeds() {
        var resp = leaderEdit(team1, cap1, List.of("13800000001", "13800000002", "13800000003", "13800000004"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"status\":\"PENDING\"");
    }

    @Test
    void rejectWithoutReasonIsRejected() {
        var resp = putJson("/api/teams/" + team1 + "/review", Map.of("action", "REJECT"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void rejectWithReasonMarksRejected() {
        var resp = putJson("/api/teams/" + team1 + "/review",
                Map.of("action", "REJECT", "reason", "名单有误"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        var detail = getJson("/api/teams/" + team1);
        assertThat(detail.getBody()).contains("\"status\":\"REJECTED\"").contains("名单有误");
    }

    @Test
    void passMarksConfirmed() {
        var resp = putJson("/api/teams/" + team1 + "/review", Map.of("action", "PASS"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        var detail = getJson("/api/teams/" + team1);
        assertThat(detail.getBody()).contains("\"status\":\"CONFIRMED\"");
    }

    @Test
    void leaderEditAfterRejectionBackToPendingAndClearsReason() {
        putJson("/api/teams/" + team1 + "/review", Map.of("action", "REJECT", "reason", "名单有误"));

        var resp = leaderEdit(team1, cap1, List.of("13800000001", "13800000002"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"status\":\"PENDING\"")
                .contains("\"rejectReason\":null");
    }

    @Test
    void adminEditMarksConfirmed() {
        var resp = putJson("/api/teams/" + team1 + "/members",
                Map.of("memberPhoneList", List.of("13800000002", "13800000003")));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"status\":\"CONFIRMED\"");
    }

    @Test
    void editsBlockedAfterWindowEndsButReviewStillWorks() {
        jdbc.update("UPDATE activity SET manually_ended = 1");

        var leaderBlocked = leaderEdit(team1, cap1, List.of("13800000001"));
        assertThat(leaderBlocked.getStatusCode().value()).isEqualTo(409);
        assertThat(leaderBlocked.getBody()).contains("WINDOW_CLOSED");

        var adminBlocked = putJson("/api/teams/" + team1 + "/members",
                Map.of("memberPhoneList", List.of("13800000001")));
        assertThat(adminBlocked.getStatusCode().value()).isEqualTo(409);

        var review = putJson("/api/teams/" + team1 + "/review", Map.of("action", "PASS"));
        assertThat(review.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void teamListFiltersByStatusAndFlagsOverLimit() {
        jdbc.update("UPDATE activity SET group_size_limit = 1");

        var rejected = putJson("/api/teams/" + team2 + "/review",
                Map.of("action", "REJECT", "reason", "重交"));
        assertThat(rejected.getStatusCode().value()).isEqualTo(200);

        var page = getJson("/api/teams?status=REJECTED&page=1&size=20");
        assertThat(page.getBody()).contains("\"total\":1").contains("\"name\":\"组2\"");

        var all = getJson("/api/teams?page=1&size=20");
        assertThat(all.getBody()).contains("\"overLimit\":true");
    }

    @Test
    void movingPersonOutOfTeamReducesJoinedCount() {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM team_member", Integer.class);
        assertThat(before).isEqualTo(5);

        leaderEdit(team1, cap1, List.of("13800000002", "13800000003"));

        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM team_member", Integer.class);
        assertThat(after).isEqualTo(4);
    }

    @Test
    void reviewOfUnknownTeamReturns404() {
        var resp = putJson("/api/teams/999/review", Map.of("action", "PASS"));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
}
