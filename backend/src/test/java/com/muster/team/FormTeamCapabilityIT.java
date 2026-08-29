package com.muster.team;

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
 * 组详情/组长改组必须携带提交时发放的 capToken，否则按 404 处理（不泄露组是否存在），
 * 防止任何扫码者遍历自增 teamId 读取全部成员 PII 或篡改其他组。
 */
class FormTeamCapabilityIT extends IntegrationTestBase {

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
                List.of("李四", "13800000002", "外语"),
                List.of("王五", "13800000003", "体育"))));
        formToken = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    private long submitTeam(String phone) throws com.fasterxml.jackson.core.JsonProcessingException {
        var resp = postJson("/api/form/" + formToken + "/teams", Map.of("memberPhoneList", List.of(phone)));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(resp.getBody()).path("id").asLong();
    }

    @Test
    void submitResponseContainsCapToken() throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams", Map.of("memberPhoneList", List.of("13800000001")));
        var cap = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(resp.getBody()).path("capToken").asText(null);
        assertThat(cap).isNotBlank().hasSize(36);
    }

    @Test
    void teamDetailRequiresCap() throws Exception {
        long teamId = submitTeam("13800000001");

        var noCap = getJson("/api/form/" + formToken + "/teams/" + teamId);
        assertThat(noCap.getStatusCode().value()).isEqualTo(404);
        assertThat(noCap.getBody()).contains("NOT_FOUND");
    }

    @Test
    void teamDetailWithWrongCapReturns404() throws Exception {
        long teamId = submitTeam("13800000001");

        var wrong = getJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=not-the-right-cap");
        assertThat(wrong.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void teamDetailWithCorrectCapReturns200() throws Exception {
        var submitted = postJson("/api/form/" + formToken + "/teams", Map.of("memberPhoneList", List.of("13800000001")));
        var body = submitted.getBody();
        long teamId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("id").asLong();
        String cap = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body)
                .path("capToken").asText();

        var resp = getJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("13800000001");
    }

    @Test
    void capOfAnotherTeamDoesNotGrantAccess() throws Exception {
        long idA = submitTeam("13800000001");
        var submittedB = postJson("/api/form/" + formToken + "/teams", Map.of("memberPhoneList", List.of("13800000002")));
        String capB = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(submittedB.getBody()).path("capToken").asText();

        var resp = getJson("/api/form/" + formToken + "/teams/" + idA + "?cap=" + capB);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void leaderEditRequiresCap() throws Exception {
        var submitted = postJson("/api/form/" + formToken + "/teams", Map.of("memberPhoneList", List.of("13800000001")));
        var tree = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(submitted.getBody());
        long teamId = tree.path("id").asLong();
        String cap = tree.path("capToken").asText();

        var denied = putJson("/api/form/" + formToken + "/teams/" + teamId,
                Map.of("memberPhoneList", List.of("13800000002")));
        assertThat(denied.getStatusCode().value()).isEqualTo(404);

        var allowed = putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap,
                Map.of("memberPhoneList", List.of("13800000002")));
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody()).contains("13800000002");
    }
}
