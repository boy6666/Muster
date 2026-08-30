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

/**
 * 组级能力令牌：二维码 token 是全体参与者共享的，不能作为"本组组长"凭证。
 * 组详情/组长改组必须携带创建组时发放的 capToken，否则按 404 处理（不泄露组是否存在），
 * 防止任何扫码者遍历自增 teamId 读取全部成员 PII 或篡改其他组。
 * 首次提交例外：必须凭组长手机号验证身份（此时可能在新设备上，没有 cap）。
 */
class FormTeamCapabilityIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private String formToken;

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
                List.of("E003", "王五", "13800000003", "体育"))));
        formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    private JsonNode createDraft(String leader, List<String> members) throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody());
    }

    private ResponseEntity<String> submitRaw(long teamId, String cap, String phone) {
        String url = "/api/form/" + formToken + "/teams/" + teamId + "/submit" + (cap == null ? "" : "?cap=" + cap);
        Map<String, Object> body = phone == null ? Map.of() : Map.of("leaderPhone", phone);
        return postJson(url, body);
    }

    @Test
    void draftResponseContainsCapToken() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        assertThat(draft.path("capToken").asText()).isNotBlank().hasSize(36);
    }

    @Test
    void teamDetailRequiresCap() throws Exception {
        long teamId = createDraft("E001", List.of("E001")).path("id").asLong();

        var noCap = getJson("/api/form/" + formToken + "/teams/" + teamId);
        assertThat(noCap.getStatusCode().value()).isEqualTo(404);
        assertThat(noCap.getBody()).contains("NOT_FOUND");
    }

    @Test
    void teamDetailWithWrongCapReturns404() throws Exception {
        long teamId = createDraft("E001", List.of("E001")).path("id").asLong();

        var wrong = getJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=not-the-right-cap");
        assertThat(wrong.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void teamDetailWithCorrectCapReturns200() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long teamId = draft.path("id").asLong();
        String cap = draft.path("capToken").asText();

        var resp = getJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("13800000001");
    }

    @Test
    void capOfAnotherTeamDoesNotGrantAccess() throws Exception {
        long idA = createDraft("E001", List.of("E001")).path("id").asLong();
        String capB = createDraft("E002", List.of("E002")).path("capToken").asText();

        var resp = getJson("/api/form/" + formToken + "/teams/" + idA + "?cap=" + capB);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void leaderSaveRequiresCap() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long teamId = draft.path("id").asLong();

        var denied = putJson("/api/form/" + formToken + "/teams/" + teamId,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E002")));
        assertThat(denied.getStatusCode().value()).isEqualTo(404);

        var allowed = putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + draft.path("capToken").asText(),
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E002")));
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody()).contains("E002");
    }

    @Test
    void leaderDeleteRequiresCap() throws Exception {
        var draft = createDraft("E001", List.of("E001"));
        long teamId = draft.path("id").asLong();

        var denied = deleteJson("/api/form/" + formToken + "/teams/" + teamId);
        assertThat(denied.getStatusCode().value()).isEqualTo(404);

        var allowed = deleteJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + draft.path("capToken").asText());
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void firstSubmitAllowedWithoutCap() throws Exception {
        // 首次提交必须有手机号校验兜底——新设备上没有 cap 也能凭组长手机号提交
        var draft = createDraft("E001", List.of("E001"));
        long teamId = draft.path("id").asLong();

        var resp = submitRaw(teamId, null, "13800000001");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(MAPPER.readTree(resp.getBody()).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void verifyThenCapWorksForSave() throws Exception {
        // 被驳回后换新设备：先凭手机号换 cap，再保存
        var draft = createDraft("E001", List.of("E001"));
        long teamId = draft.path("id").asLong();
        submitRaw(teamId, null, "13800000001");
        putJson("/api/teams/" + teamId + "/review", Map.of("action", "REJECT", "reason", "信息有误"));

        var verified = postJson("/api/form/" + formToken + "/teams/" + teamId + "/verify",
                Map.of("leaderPhone", "13800000001"));
        assertThat(verified.getStatusCode().value()).isEqualTo(200);
        String cap = MAPPER.readTree(verified.getBody()).path("capToken").asText();
        assertThat(cap).isNotBlank();

        var saved = putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E002")));
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
    }
}
