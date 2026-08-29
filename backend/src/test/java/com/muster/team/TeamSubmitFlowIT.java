package com.muster.team;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamSubmitFlowIT extends IntegrationTestBase {

    private String formToken;

    private static final List<List<Object>> ROSTER = List.of(
            List.of("张三", "13800000001", "计算机"),
            List.of("李四", "13800000002", "外语"),
            List.of("王五", "13800000003", "体育"),
            List.of("赵六", "13800000004", "数学"),
            List.of("钱七", "13800000005", "物理"));

    @BeforeEach
    void setupActiveActivityWithRoster() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(ROSTER));
        formToken = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    private ResponseEntity<String> submit(List<String> phones) {
        return postJson("/api/form/" + formToken + "/teams", Map.of("memberPhoneList", phones));
    }

    @Test
    void formInfoReturnsActiveActivityFields() {
        var resp = getJson("/api/form/" + formToken);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"name\":\"迎新晚会\"")
                .contains("\"groupSizeLimit\":5")
                .contains("\"windowStatus\":\"ACTIVE\"");
    }

    @Test
    void wrongTokenReturns404() {
        var resp = getJson("/api/form/not-a-real-token");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }

    @Test
    void submitCreatesPendingTeamWithThreeMembers() {
        var resp = submit(List.of("13800000001", "13800000002", "13800000003"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"name\":\"组1\"")
                .contains("\"status\":\"PENDING\"")
                .contains("\"overLimit\":false")
                .contains("13800000001")
                .contains("13800000002")
                .contains("13800000003");
    }

    @Test
    void submittingPersonAlreadyInOtherTeamConflicts() {
        submit(List.of("13800000001", "13800000002"));
        var again = submit(List.of("13800000002", "13800000003"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("CONFLICT").contains("组1");
    }

    @Test
    void unknownPhoneReturns404() {
        var resp = submit(List.of("13800000001", "13999999999"));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("PERSON_NOT_FOUND").contains("13999999999");
    }

    @Test
    void invalidPhoneFormatReturns400() {
        var resp = submit(List.of("12345"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void emptyMemberListReturns400() {
        var resp = submit(List.of());
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void overLimitSubmitSucceedsWithFlag() {
        var resp = submit(List.of(
                "13800000001", "13800000002", "13800000003", "13800000004", "13800000005",
                "13900000001"));
        // 6 人超过上限 5，但 13900000001 不在花名册 → 先补进花名册再测
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void overLimitWithinRosterSucceedsWithFlag() {
        byte[] extra = rosterWorkbook(List.of(List.of("孙八", "13900000001", "化学")));
        uploadRoster(extra);

        var resp = submit(List.of(
                "13800000001", "13800000002", "13800000003", "13800000004", "13800000005",
                "13900000001"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"overLimit\":true").contains("\"name\":\"组1\"");
    }

    @Test
    void closedWindowRejectsSubmission() {
        jdbc.update("UPDATE activity SET manually_ended = 1");
        var resp = submit(List.of("13800000001"));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("WINDOW_CLOSED");
    }

    @Test
    void notStartedWindowRejectsSubmission() {
        jdbc.update("UPDATE activity SET start_time = ?", LocalDateTime.now(clock).plusHours(2));
        var resp = submit(List.of("13800000001"));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("WINDOW_CLOSED");
    }

    @Test
    void myTeamEndpointReturnsSameDetail() throws Exception {
        var submitted = submit(List.of("13800000001", "13800000002"));
        var teamId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(submitted.getBody()).path("id").asLong();

        var resp = getJson("/api/form/" + formToken + "/teams/" + teamId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"name\":\"组1\"")
                .contains("13800000001")
                .contains("13800000002");
    }

    @Test
    void myTeamWithUnknownTeamIdReturns404() {
        submit(List.of("13800000001"));
        var resp = getJson("/api/form/" + formToken + "/teams/999");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }
}
