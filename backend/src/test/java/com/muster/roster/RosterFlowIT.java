package com.muster.roster;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RosterFlowIT extends IntegrationTestBase {

    @BeforeEach
    void createActivity() {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
    }

    @Test
    void templateDownloadsAsXlsx() {
        var resp = getBytes("/api/roster/template");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString())
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void importThreePersonsThenListThem() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语"),
                List.of("E003", "王五", "13600000000", "体育")));
        var resp = uploadRoster(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"imported\":3");

        var list = getJson("/api/roster?page=1&size=20");
        assertThat(list.getBody()).contains("\"total\":3").contains("张三").contains("E002").contains("13987654321");
    }

    @Test
    void fuzzySearchHitsEmployeeIdPhoneNameAndDepartment() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语"),
                List.of("E003", "王五", "13600000000", "体育")));
        uploadRoster(bytes);

        assertThat(getJson("/api/roster?keyword=45678").getBody()).contains("张三").doesNotContain("李四");
        assertThat(getJson("/api/roster?keyword=王五").getBody()).contains("13600000000").doesNotContain("张三");
        assertThat(getJson("/api/roster?keyword=外语").getBody()).contains("李四").doesNotContain("王五");
    }

    @Test
    void invalidRowIsRejectedWithRowNumber() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "bad", "外语")));
        var resp = uploadRoster(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION").contains("3");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void invalidEmployeeIdRowIsRejected() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E 01", "张三", "13812345678", "计算机")));
        var resp = uploadRoster(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION").contains("第 2 行");
    }

    @Test
    void duplicateEmployeeIdInFileRollsBackWholeImport() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E001", "李四", "13987654321", "外语")));
        var resp = uploadRoster(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("DUPLICATE");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void duplicatePhoneInFileRollsBackWholeImport() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13812345678", "外语")));
        var resp = uploadRoster(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("PHONE_DUPLICATE");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void employeeIdClashWithExistingPersonRejected() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语")));
        uploadRoster(bytes);

        byte[] again = rosterWorkbook(List.of(List.of("E001", "赵六", "13600000001", "数学")));
        var resp = uploadRoster(again);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("DUPLICATE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isEqualTo(2);
    }

    @Test
    void duplicateAgainstExistingPhoneRejected() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语")));
        uploadRoster(bytes);

        byte[] again = rosterWorkbook(List.of(List.of("E003", "赵六", "13987654321", "数学")));
        var resp = uploadRoster(again);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("PHONE_DUPLICATE");
    }

    @Test
    void addPersonManuallyThenDuplicateRejected() {
        var ok = postJson("/api/roster", Map.of(
                "employeeId", "E001", "name", "张三", "phone", "13812345678", "department", "计算机"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).contains("张三").contains("E001");

        var missingId = postJson("/api/roster", Map.of(
                "name", "王五", "phone", "13800000009", "department", "体育"));
        assertThat(missingId.getStatusCode().value()).isEqualTo(400);

        var dupEmployee = postJson("/api/roster", Map.of(
                "employeeId", "E001", "name", "李四", "phone", "13987654321", "department", "外语"));
        assertThat(dupEmployee.getStatusCode().value()).isEqualTo(400);
        assertThat(dupEmployee.getBody()).contains("DUPLICATE");

        var dupPhone = postJson("/api/roster", Map.of(
                "employeeId", "E002", "name", "李四", "phone", "13812345678", "department", "外语"));
        assertThat(dupPhone.getStatusCode().value()).isEqualTo(400);
        assertThat(dupPhone.getBody()).contains("PHONE_DUPLICATE");

        var badPhone = postJson("/api/roster", Map.of(
                "employeeId", "E003", "name", "王五", "phone", "123", "department", "体育"));
        assertThat(badPhone.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deletePersonAlsoRemovesTeamMembership() {
        byte[] bytes = rosterWorkbook(List.of(List.of("E001", "张三", "13812345678", "计算机")));
        uploadRoster(bytes);
        Long personId = jdbc.queryForObject("SELECT id FROM person LIMIT 1", Long.class);

        jdbc.update("INSERT INTO team(activity_id, name, status, submitted_at) VALUES(1, '组1', 'PENDING', NOW())");
        Long teamId = jdbc.queryForObject("SELECT id FROM team LIMIT 1", Long.class);
        jdbc.update("INSERT INTO team_member(team_id, person_id) VALUES(?, ?)", teamId, personId);

        var resp = deleteJson("/api/roster/" + personId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_member", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void deleteUnknownPersonReturns404() {
        var resp = deleteJson("/api/roster/999");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }

    @Test
    void editPersonUpdatesFields() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语")));
        uploadRoster(bytes);
        Long e001Id = jdbc.queryForObject(
                "SELECT id FROM person WHERE employee_id = 'E001'", Long.class);

        var resp = putJson("/api/roster/" + e001Id, Map.of(
                "employeeId", "E100", "name", "张三改", "phone", "13812340000", "department", "数学系"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("E100").contains("张三改").contains("数学系");

        var list = getJson("/api/roster?keyword=张三改");
        assertThat(list.getBody()).contains("E100").doesNotContain("E001");
    }

    @Test
    void editRejectsDuplicateEmployeeId() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语")));
        uploadRoster(bytes);
        Long e002Id = jdbc.queryForObject(
                "SELECT id FROM person WHERE employee_id = 'E002'", Long.class);

        var resp = putJson("/api/roster/" + e002Id, Map.of(
                "employeeId", "E001", "name", "李四", "phone", "13987654321", "department", "外语"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("DUPLICATE");
    }

    @Test
    void editRejectsDuplicatePhone() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语")));
        uploadRoster(bytes);
        Long e002Id = jdbc.queryForObject(
                "SELECT id FROM person WHERE employee_id = 'E002'", Long.class);

        var resp = putJson("/api/roster/" + e002Id, Map.of(
                "employeeId", "E002", "name", "李四", "phone", "13812345678", "department", "外语"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("PHONE_DUPLICATE");
    }

    @Test
    void clearRemovesAllWhenNoTeams() {
        byte[] bytes = rosterWorkbook(List.of(
                List.of("E001", "张三", "13812345678", "计算机"),
                List.of("E002", "李四", "13987654321", "外语"),
                List.of("E003", "王五", "13600000000", "体育")));
        uploadRoster(bytes);

        var resp = deleteJson("/api/roster");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"deleted\":3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void clearBlockedWhenTeamsExist() {
        byte[] bytes = rosterWorkbook(List.of(List.of("E001", "张三", "13812345678", "计算机")));
        uploadRoster(bytes);
        Long activityId = jdbc.queryForObject("SELECT id FROM activity LIMIT 1", Long.class);

        jdbc.update("INSERT INTO team(activity_id, name, status, submitted_at) VALUES(?, '组1', 'PENDING', NOW())",
                activityId);

        var resp = deleteJson("/api/roster");
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isEqualTo(1);
    }
}
