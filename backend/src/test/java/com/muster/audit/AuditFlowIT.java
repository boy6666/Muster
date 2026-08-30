package com.muster.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFlowIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private String formToken;

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
                List.of("E003", "王五", "13800000003", "体育"))));
        formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    private JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private List<JsonNode> teamEvents(long teamId) throws Exception {
        JsonNode array = json(getJson("/api/teams/" + teamId + "/events").getBody());
        List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    @Test
    void adminActionsAreRecordedInOpLog() throws Exception {
        // 建活动 / 导入花名册 / 单个添加 / 编辑 / 单个删除 / 管理员建组 / 删组 / 一键清空 均应落审计日志
        postJson("/api/roster", Map.of(
                "employeeId", "E004", "name", "孙八", "phone", "13800000004", "department", "化学"));
        long pid = json(getJson("/api/roster?keyword=E004").getBody())
                .path("records").get(0).path("id").asLong();
        putJson("/api/roster/" + pid, Map.of(
                "employeeId", "E004", "name", "孙八", "phone", "13800000004", "department", "数学"));
        deleteJson("/api/roster/" + pid);

        var team = json(postJson("/api/teams", Map.of(
                "leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001"))).getBody());
        deleteJson("/api/teams/" + team.path("id").asLong());

        deleteJson("/api/roster");

        var logs = json(getJson("/api/audit/logs?page=1&size=50").getBody());
        assertThat(logs.path("total").asLong()).isGreaterThanOrEqualTo(8);
        var actions = new java.util.HashSet<String>();
        for (JsonNode row : logs.path("records")) {
            actions.add(row.path("action").asText());
            assertThat(row.path("adminUsername").asText()).isEqualTo("admin");
        }
        assertThat(actions).contains(
                "ACTIVITY_CREATE", "ROSTER_IMPORT", "ROSTER_ADD", "ROSTER_EDIT", "ROSTER_DELETE",
                "TEAM_CREATE_ADMIN", "TEAM_DELETE_ADMIN", "ROSTER_CLEAR");
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
        // 草稿 → 组长保存 → 提交 → 管理员改组 → 驳回 → 通过，共 6 个事件
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001", "E002")));
        JsonNode team = json(resp.getBody());
        long teamId = team.path("id").asLong();
        String cap = team.path("capToken").asText();

        putJson("/api/form/" + formToken + "/teams/" + teamId + "?cap=" + cap,
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001")));
        postJson("/api/form/" + formToken + "/teams/" + teamId + "/submit", Map.of("leaderPhone", "13800000001"));
        putJson("/api/teams/" + teamId + "/members",
                Map.of("memberEmployeeIdList", List.of("E001", "E002")));
        putJson("/api/teams/" + teamId + "/review", Map.of("action", "REJECT", "reason", "名单有误"));
        putJson("/api/teams/" + teamId + "/review", Map.of("action", "PASS"));

        List<JsonNode> events = teamEvents(teamId);
        assertThat(events).hasSize(6);
        assertThat(events.get(0).path("type").asText()).isEqualTo("CREATED");
        assertThat(events.get(1).path("type").asText()).isEqualTo("SAVED");
        assertThat(events.get(2).path("type").asText()).isEqualTo("SUBMITTED");
        assertThat(events.get(3).path("type").asText()).isEqualTo("EDITED_BY_ADMIN");
        assertThat(events.get(4).path("type").asText()).isEqualTo("REJECTED");
        assertThat(events.get(4).path("detail").asText()).contains("名单有误");
        assertThat(events.get(5).path("type").asText()).isEqualTo("PASSED");
    }

    @Test
    void eventsOfOtherTeamNotLeaked() throws Exception {
        long teamId = json(postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E001", "memberEmployeeIdList", List.of("E001"))).getBody())
                .path("id").asLong();
        long team2 = json(postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", "E002", "memberEmployeeIdList", List.of("E002"))).getBody())
                .path("id").asLong();
        assertThat(teamEvents(teamId)).hasSize(1);
        assertThat(teamEvents(team2)).hasSize(1);
    }
}
