package com.muster.roster;

import com.alibaba.excel.EasyExcel;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
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

    private byte[] workbookOf(List<List<Object>> dataRows) {
        var head = List.of(List.of("姓名"), List.of("手机号"), List.of("部门"));
        var out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head).sheet("花名册").doWrite(dataRows);
        return out.toByteArray();
    }

    private ResponseEntity<String> upload(byte[] bytes) {
        var resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "roster.xlsx";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/api/roster/import", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void templateDownloadsAsXlsx() {
        var resp = getJson("/api/roster/template");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString())
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void importThreePersonsThenListThem() {
        byte[] bytes = workbookOf(List.of(
                List.of("张三", "13812345678", "计算机"),
                List.of("李四", "13987654321", "外语"),
                List.of("王五", "13600000000", "体育")));
        var resp = upload(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"imported\":3");

        var list = getJson("/api/roster?page=1&size=20");
        assertThat(list.getBody()).contains("\"total\":3").contains("张三").contains("13987654321");
    }

    @Test
    void fuzzySearchHitsPhoneNameAndDepartment() {
        byte[] bytes = workbookOf(List.of(
                List.of("张三", "13812345678", "计算机"),
                List.of("李四", "13987654321", "外语"),
                List.of("王五", "13600000000", "体育")));
        upload(bytes);

        assertThat(getJson("/api/roster?keyword=45678").getBody()).contains("张三").doesNotContain("李四");
        assertThat(getJson("/api/roster?keyword=王五").getBody()).contains("13600000000").doesNotContain("张三");
        assertThat(getJson("/api/roster?keyword=外语").getBody()).contains("李四").doesNotContain("王五");
    }

    @Test
    void invalidRowIsRejectedWithRowNumber() {
        byte[] bytes = workbookOf(List.of(
                List.of("张三", "13812345678", "计算机"),
                List.of("李四", "bad", "外语")));
        var resp = upload(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION").contains("3");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void duplicateInFileRollsBackWholeImport() {
        byte[] bytes = workbookOf(List.of(
                List.of("张三", "13812345678", "计算机"),
                List.of("李四", "13812345678", "外语")));
        var resp = upload(bytes);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("PHONE_DUPLICATE");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM person", Integer.class)).isZero();
    }

    @Test
    void duplicateAgainstExistingPersonRejected() {
        byte[] bytes = workbookOf(List.of(
                List.of("张三", "13812345678", "计算机"),
                List.of("李四", "13987654321", "外语")));
        upload(bytes);

        byte[] again = workbookOf(List.of(List.of("赵六", "13987654321", "数学")));
        var resp = upload(again);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("PHONE_DUPLICATE");
    }

    @Test
    void addPersonManuallyThenDuplicateRejected() {
        var ok = postJson("/api/roster", Map.of("name", "张三", "phone", "13812345678", "department", "计算机"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).contains("张三");

        var dup = postJson("/api/roster", Map.of("name", "李四", "phone", "13812345678", "department", "外语"));
        assertThat(dup.getStatusCode().value()).isEqualTo(400);
        assertThat(dup.getBody()).contains("PHONE_DUPLICATE");

        var badPhone = postJson("/api/roster", Map.of("name", "王五", "phone", "123", "department", "体育"));
        assertThat(badPhone.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deletePersonAlsoRemovesTeamMembership() {
        byte[] bytes = workbookOf(List.of(List.of("张三", "13812345678", "计算机")));
        upload(bytes);
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
}
