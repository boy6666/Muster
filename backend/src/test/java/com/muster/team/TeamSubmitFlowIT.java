package com.muster.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamSubmitFlowIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private String formToken;

    private static final List<List<Object>> ROSTER = List.of(
            List.of("E001", "张三", "13800000001", "计算机"),
            List.of("E002", "李四", "13800000002", "外语"),
            List.of("E003", "王五", "13800000003", "体育"),
            List.of("E004", "赵六", "13800000004", "数学"),
            List.of("E005", "钱七", "13800000005", "物理"));

    @BeforeEach
    void setupActiveActivityWithRoster() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        uploadRoster(rosterWorkbook(ROSTER));
        formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    private JsonNode createDraft(String leader, List<String> members) throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody());
    }

    /** phone 传 null 时提交空 body（重提交场景可凭 cap）。 */
    private ResponseEntity<String> submitRaw(long teamId, String cap, String phone) {
        String url = "/api/form/" + formToken + "/teams/" + teamId + "/submit" + (cap == null ? "" : "?cap=" + cap);
        Map<String, Object> body = phone == null ? Map.of() : Map.of("leaderPhone", phone);
        return postJson(url, body);
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
    void personLookupByExactEmployeeId() throws Exception {
        var resp = getJson("/api/form/" + formToken + "/person?employeeId=E002");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"employeeId\":\"E002\"")
                .contains("\"name\":\"李四\"")
                .contains("\"department\":\"外语\"");
        assertThat(MAPPER.readTree(resp.getBody()).path("teamId").isNull()).isTrue();
    }

    @Test
    void personLookupRejectsPartialEmployeeId() {
        var resp = getJson("/api/form/" + formToken + "/person?employeeId=E0");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void personLookupUnknownEmployeeIdReturns404() {
        var resp = getJson("/api/form/" + formToken + "/person?employeeId=E999");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("PERSON_NOT_FOUND");
    }

    @Test
    void personLookupWrongTokenReturns404() {
        var resp = getJson("/api/form/not-a-real-token/person?employeeId=E001");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }

    @Test
    void personLookupShowsTeamAfterDraft() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));

        var resp = getJson("/api/form/" + formToken + "/person?employeeId=E001");
        var node = MAPPER.readTree(resp.getBody());
        assertThat(node.path("teamId").asLong()).isEqualTo(draft.path("id").asLong());
        assertThat(node.path("leader").asBoolean()).isTrue();
    }

    @Test
    void createDraftStoresCapAndMembers() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("capToken").asText()).hasSize(36);
        assertThat(draft.path("submittedAt").isNull()).isTrue();
        assertThat(draft.path("members").size()).isEqualTo(2);
        assertThat(draft.path("members").get(0).path("employeeId").asText()).isEqualTo("E001");
        assertThat(draft.path("members").get(0).path("isLeader").asBoolean()).isTrue();
        assertThat(draft.path("members").get(1).path("employeeId").asText()).isEqualTo("E002");
        assertThat(draft.path("members").get(1).path("isLeader").asBoolean()).isFalse();
    }

    @Test
    void createDraftConflictWhenMemberTaken() throws Exception {
        createDraft("E001", List.of("E001", "E002"));
        var again = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E003", "memberEmployeeIdList", List.of("E003", "E002")));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("CONFLICT").contains("组1").contains("E002");
    }

    @Test
    void firstSubmitRejectsWrongPhone() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();

        var resp = submitRaw(id, null, "13800000009");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("组长手机号不正确");

        var detail = getJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap);
        assertThat(MAPPER.readTree(detail.getBody()).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void firstSubmitWithCorrectPhoneGoesPending() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));

        var resp = submitRaw(draft.path("id").asLong(), null, "13800000001");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(MAPPER.readTree(resp.getBody()).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void firstSubmitRejectsMalformedPhone() throws Exception {
        var draft = createDraft("E001", List.of("E001"));

        var resp = submitRaw(draft.path("id").asLong(), null, "123");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void resubmitAfterRejectionWithCap() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();
        assertThat(submitRaw(id, null, "13800000001").getStatusCode().value()).isEqualTo(200);
        putJson("/api/teams/" + id + "/review", Map.of("action", "REJECT", "reason", "信息有误"));

        var resp = submitRaw(id, cap, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(MAPPER.readTree(resp.getBody()).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void resubmitOnNewDeviceViaPhone() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long id = draft.path("id").asLong();
        assertThat(submitRaw(id, null, "13800000001").getStatusCode().value()).isEqualTo(200);
        putJson("/api/teams/" + id + "/review", Map.of("action", "REJECT", "reason", "信息有误"));

        // 无 cap，凭组长手机号重提交
        var withPhone = submitRaw(id, null, "13800000001");
        assertThat(withPhone.getStatusCode().value()).isEqualTo(200);

        // 无 cap 也无手机号 → 404（不泄露组是否存在）
        var draft2 = createDraft("E002", List.of("E002"));
        long id2 = draft2.path("id").asLong();
        submitRaw(id2, null, "13800000002");
        putJson("/api/teams/" + id2 + "/review", Map.of("action", "REJECT", "reason", "x"));
        var noProof = submitRaw(id2, null, null);
        assertThat(noProof.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void myTeamReturnsLeaderView() throws Exception {
        createDraft("E001", List.of("E001", "E002"));

        var leader = getJson("/api/form/" + formToken + "/my-team?employeeId=E001");
        assertThat(leader.getStatusCode().value()).isEqualTo(200);
        assertThat(MAPPER.readTree(leader.getBody()).path("isLeader").asBoolean()).isTrue();

        var member = getJson("/api/form/" + formToken + "/my-team?employeeId=E002");
        assertThat(member.getStatusCode().value()).isEqualTo(200);
        assertThat(MAPPER.readTree(member.getBody()).path("isLeader").asBoolean()).isFalse();
    }

    @Test
    void myTeamOmitsCapToken() throws Exception {
        createDraft("E001", List.of("E001"));

        var resp = getJson("/api/form/" + formToken + "/my-team?employeeId=E001");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).doesNotContain("capToken");
    }

    @Test
    void myTeamWithoutMembershipReturns404() {
        var resp = getJson("/api/form/" + formToken + "/my-team?employeeId=E005");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }

    @Test
    void pendingLockedForSaveAndDelete() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();
        submitRaw(id, null, "13800000001");

        var save = putJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E003")));
        assertThat(save.getStatusCode().value()).isEqualTo(409);
        assertThat(save.getBody()).contains("审核中");

        var del = deleteJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap);
        assertThat(del.getStatusCode().value()).isEqualTo(409);
        assertThat(del.getBody()).contains("审核中");
    }

    @Test
    void saveKeepsDraftStatus() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();

        var saved = putJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E003")));
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        var node = MAPPER.readTree(saved.getBody());
        assertThat(node.path("status").asText()).isEqualTo("DRAFT");
        assertThat(node.path("members").size()).isEqualTo(2);
        assertThat(node.path("members").get(1).path("employeeId").asText()).isEqualTo("E003");
    }

    @Test
    void saveWithUnknownEmployeeIdReturns404() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();

        var resp = putJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E999")));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("未在花名册中：E999");
    }

    @Test
    void saveRequiresLeaderInMembers() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();

        var resp = putJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E002", "E003")));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void createDraftBlockedWhenWindowClosed() {
        jdbc.update("UPDATE activity SET manually_ended = 1");
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001")));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("WINDOW_CLOSED");
    }

    @Test
    void deleteDraftByLeader() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        long id = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();

        var del = deleteJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap);
        assertThat(del.getStatusCode().value()).isEqualTo(200);

        var lookup = getJson("/api/form/" + formToken + "/person?employeeId=E001");
        assertThat(MAPPER.readTree(lookup.getBody()).path("teamId").isNull()).isTrue();
    }

    @Test
    void verifyExchangesPhoneForCap() throws Exception {
        var draft = createDraft("E001", List.of("E001", "E002"));
        long id = draft.path("id").asLong();

        var wrong = postJson("/api/form/" + formToken + "/teams/" + id + "/verify",
                Map.of("leaderPhone", "13800000009"));
        assertThat(wrong.getStatusCode().value()).isEqualTo(400);

        var ok = postJson("/api/form/" + formToken + "/teams/" + id + "/verify",
                Map.of("leaderPhone", "13800000001"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        String cap = MAPPER.readTree(ok.getBody()).path("capToken").asText();
        assertThat(cap).isNotBlank();

        var detail = getJson("/api/form/" + formToken + "/teams/" + id + "?cap=" + cap);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void verifyOnlyForDraftOrRejected() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long id = draft.path("id").asLong();
        submitRaw(id, null, "13800000001");

        var resp = postJson("/api/form/" + formToken + "/teams/" + id + "/verify",
                Map.of("leaderPhone", "13800000001"));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void overLimitWithinRosterSucceedsWithFlag() throws Exception {
        uploadRoster(rosterWorkbook(List.of(List.of("E006", "孙八", "13900000001", "化学"))));

        var draft = createDraft("E001", List.of("E001", "E002", "E003", "E004", "E005", "E006"));
        assertThat(draft.path("overLimit").asBoolean()).isTrue();
        assertThat(draft.path("name").asText()).isEqualTo("组1");
    }
}
