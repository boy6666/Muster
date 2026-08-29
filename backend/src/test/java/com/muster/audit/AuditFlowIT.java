package com.muster.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFlowIT extends IntegrationTestBase {

    private String formToken;

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
                List.of("王五", "13800000003", "体育"))));
        formToken = json(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    private JsonNode json(String body) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
    }

    private JsonNode submitTeam() throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000001", "13800000002")));
        return json(resp.getBody());
    }

    private List<JsonNode> teamEvents(long teamId) throws Exception {
        JsonNode array = json(getJson("/api/teams/" + teamId + "/events").getBody());
        List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    @Test
    void adminActionsAreRecordedInOpLog() throws Exception {
        // 建活动 / 导入花名册 / 单个添加 / 单个删除 均应落审计日志
        postJson("/api/roster", Map.of("name", "孙八", "phone", "13800000004", "department", "化学"));
        long pid = 0;
        var search = json(getJson("/api/roster?keyword=孙八").getBody());
        pid = search.path("records").get(0).path("id").asLong();
        deleteJson("/api/roster/" + pid);

        var logs = json(getJson("/api/audit/logs?page=1&size=50").getBody());
        assertThat(logs.path("total").asLong()).isGreaterThanOrEqualTo(4);
        var actions = new java.util.HashSet<String>();
        for (JsonNode row : logs.path("records")) {
            actions.add(row.path("action").asText());
            assertThat(row.path("adminUsername").asText()).isEqualTo("admin");
        }
        assertThat(actions).contains("ACTIVITY_CREATE", "ROSTER_IMPORT", "ROSTER_ADD", "ROSTER_DELETE");
    }

    @Test
    void opLogFilterableByAction() throws Exception {
        var logs = json(getJson("/api/audit/logs?action=ACTIVITY_CREATE&page=1&size=10").getBody());
        assertThat(logs.path("total").asLong()).isEqualTo(1);
        assertThat(logs.path("records").get(0).path("action").asText()).isEqualTo("ACTIVITY_CREATE");
    }

    @Test
    void auditLogRequiresLogin() {
        token = null;
        var resp = getJson("/api/audit/logs?page=1&size=10");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void teamEventsRecordFullLifecycle() throws Exception {
        JsonNode team = submitTeam();
        long teamId = team.path("id").asLong();
        String cap = team.path("capToken").asText();

        // 组长改组
        putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap,
                Map.of("memberPhoneList", List.of("13800000001")));
        // 管理员改组
        putJson("/api/teams/" + teamId + "/members",
                Map.of("memberPhoneList", List.of("13800000001", "13800000002")));
        // 驳回 → 通过
        putJson("/api/teams/" + teamId + "/review", Map.of("action", "REJECT", "reason", "名单有误"));
        putJson("/api/teams/" + teamId + "/review", Map.of("action", "PASS"));

        List<JsonNode> events = teamEvents(teamId);
        assertThat(events).hasSize(5);
        assertThat(events.get(0).path("type").asText()).isEqualTo("SUBMITTED");
        assertThat(events.get(1).path("type").asText()).isEqualTo("EDITED_BY_LEADER");
        assertThat(events.get(2).path("type").asText()).isEqualTo("EDITED_BY_ADMIN");
        assertThat(events.get(3).path("type").asText()).isEqualTo("REJECTED");
        assertThat(events.get(3).path("detail").asText()).contains("名单有误");
        assertThat(events.get(4).path("type").asText()).isEqualTo("PASSED");
    }

    @Test
    void eventsOfOtherTeamNotLeaked() throws Exception {
        long teamId = submitTeam().path("id").asLong();
        var second = postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000003")));
        long team2 = json(second.getBody()).path("id").asLong();
        assertThat(teamEvents(teamId)).hasSize(1);
        assertThat(teamEvents(team2)).hasSize(1);
    }
}
