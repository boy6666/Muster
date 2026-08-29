package com.muster.activity;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityFlowIT extends IntegrationTestBase {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 1, 18, 0);

    private Map<String, Object> createBody() {
        return Map.of("name", "迎新晚会", "startTime", START.toString(), "endTime", END.toString(),
                "groupSizeLimit", 5);
    }

    @Test
    void createThenGetReturnsSameFields() {
        var create = postJson("/api/activity", createBody());
        assertThat(create.getStatusCode().value()).isEqualTo(200);

        var get = getJson("/api/activity");
        assertThat(get.getStatusCode().value()).isEqualTo(200);
        assertThat(get.getBody())
                .contains("\"name\":\"迎新晚会\"")
                .contains("\"groupSizeLimit\":5")
                .contains("2026-09-01T09:00")
                .contains("\"exported\":false");
    }

    @Test
    void secondCreateIsBlockedUntilExported() {
        postJson("/api/activity", createBody());

        var again = postJson("/api/activity", createBody());
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("ARCHIVE_REQUIRED");
    }

    @Test
    void createAfterExportedRequiresDeleteFirst() {
        postJson("/api/activity", createBody());
        jdbc.update("UPDATE activity SET exported = 1");

        var again = postJson("/api/activity", createBody());
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("CONFLICT");
    }

    @Test
    void updateTimesAllowedOnlyBeforeStart() {
        postJson("/api/activity", createBody());

        var ok = putJson("/api/activity", Map.of(
                "startTime", START.plusDays(1).toString(), "endTime", END.plusDays(1).toString()));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);

        // 窗口进行中（活动时间改为已开始），不允许再改时间
        jdbc.update("UPDATE activity SET start_time = '2026-08-01T09:00:00', end_time = '2026-09-30T18:00:00'");
        var blocked = putJson("/api/activity", Map.of("endTime", END.plusDays(1).toString()));
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);
        assertThat(blocked.getBody()).contains("WINDOW_CLOSED");
    }

    @Test
    void endMarksManuallyEnded() {
        postJson("/api/activity", createBody());

        var resp = postJson("/api/activity/end", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        var row = jdbc.queryForMap("SELECT manually_ended FROM activity LIMIT 1");
        assertThat(row.get("manually_ended")).isEqualTo(true);
    }

    @Test
    void deleteRequiresExportedArchive() {
        postJson("/api/activity", createBody());

        var blocked = deleteJson("/api/activity");
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);
        assertThat(blocked.getBody()).contains("ARCHIVE_REQUIRED");

        jdbc.update("UPDATE activity SET exported = 1");
        var ok = deleteJson("/api/activity");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);

        var get = getJson("/api/activity");
        assertThat(get.getStatusCode().value()).isEqualTo(200);
        assertThat(get.getBody() == null || get.getBody().isBlank()).isTrue();
    }

    @Test
    void startAfterEndIsRejected() {
        var resp = postJson("/api/activity", Map.of(
                "name", "坏活动", "startTime", END.toString(), "endTime", START.toString(),
                "groupSizeLimit", 5));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void activityEndpointsRequireAuth() {
        var resp = rest.getForEntity("/api/activity", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }
}
