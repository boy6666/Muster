package com.muster.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组人数分布口径：与分组数一致（含 DRAFT 组）；overLimit = size > 活动每组上限；按 size 升序。
 */
class DistributionIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void distributionBucketsByTeamSize() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        List<List<Object>> roster = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            roster.add(List.of(String.format("E%03d", i), "成员" + i,
                    String.format("138%08d", i), "部门" + (i % 3)));
        }
        uploadRoster(rosterWorkbook(roster));
        String formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();

        // 5 个组，人数 3,3,4,5,6；前三个保持 DRAFT，后两个提交，验证 DRAFT 也计入
        createDraft(formToken, "E001", List.of("E001", "E002", "E003"));
        createDraft(formToken, "E004", List.of("E004", "E005", "E006"));
        createDraft(formToken, "E007", List.of("E007", "E008", "E009", "E010"));
        long team4 = createDraft(formToken, "E011", List.of("E011", "E012", "E013", "E014", "E015"));
        long team5 = createDraft(formToken, "E016",
                List.of("E016", "E017", "E018", "E019", "E020", "E021"));
        postJson("/api/form/" + formToken + "/teams/" + team4 + "/submit",
                Map.of("leaderPhone", "13800000011"));
        postJson("/api/form/" + formToken + "/teams/" + team5 + "/submit",
                Map.of("leaderPhone", "13800000016"));

        var resp = getJson("/api/stats/distribution");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode buckets = MAPPER.readTree(resp.getBody());
        assertThat(buckets.isArray()).isTrue();
        assertThat(buckets).hasSize(4);
        assertThat(buckets.get(0).path("size").asLong()).isEqualTo(3);
        assertThat(buckets.get(0).path("count").asLong()).isEqualTo(2);
        assertThat(buckets.get(0).path("overLimit").asBoolean()).isFalse();
        assertThat(buckets.get(1).path("size").asLong()).isEqualTo(4);
        assertThat(buckets.get(1).path("count").asLong()).isEqualTo(1);
        assertThat(buckets.get(1).path("overLimit").asBoolean()).isFalse();
        assertThat(buckets.get(2).path("size").asLong()).isEqualTo(5);
        assertThat(buckets.get(2).path("count").asLong()).isEqualTo(1);
        assertThat(buckets.get(2).path("overLimit").asBoolean()).isFalse();
        assertThat(buckets.get(3).path("size").asLong()).isEqualTo(6);
        assertThat(buckets.get(3).path("count").asLong()).isEqualTo(1);
        assertThat(buckets.get(3).path("overLimit").asBoolean()).isTrue();
    }

    @Test
    void distributionEmptyWithoutActivity() throws Exception {
        var resp = getJson("/api/stats/distribution");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode buckets = MAPPER.readTree(resp.getBody());
        assertThat(buckets.isArray()).isTrue();
        assertThat(buckets).isEmpty();
    }

    private long createDraft(String formToken, String leader, List<String> members) throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody()).path("id").asLong();
    }
}
