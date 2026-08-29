package com.muster.stats;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatsFlowIT extends IntegrationTestBase {

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
                List.of("王五", "13800000003", "体育"),
                List.of("赵六", "13800000004", "数学"),
                List.of("钱七", "13800000005", "物理"))));
        formToken = json(getJson("/api/activity").getBody()).path("qrToken").asText();

        postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000001", "13800000002")));
        var second = postJson("/api/form/" + formToken + "/teams",
                Map.of("memberPhoneList", List.of("13800000003")));
        long team2 = json(second.getBody()).path("id").asLong();
        putJson("/api/teams/" + team2 + "/review", Map.of("action", "PASS"));
    }

    private JsonNode json(String body) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
    }

    private long team1Id() throws Exception {
        var records = json(getJson("/api/teams?page=1&size=20").getBody()).path("records");
        for (JsonNode record : records) {
            if ("组1".equals(record.path("name").asText())) {
                return record.path("id").asLong();
            }
        }
        throw new IllegalStateException("组1 not found");
    }

    @Test
    void statsCountsCorrectly() throws Exception {
        var resp = getJson("/api/stats");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode stats = json(resp.getBody());
        assertThat(stats.path("total").asLong()).isEqualTo(5);
        assertThat(stats.path("joined").asLong()).isEqualTo(3);
        assertThat(stats.path("notJoined").asLong()).isEqualTo(2);
        assertThat(stats.path("teamCount").asLong()).isEqualTo(2);
        assertThat(stats.path("pendingTeamCount").asLong()).isEqualTo(1);
    }

    @Test
    void websocketPushesInitialFrameAndUpdates() throws Exception {
        FrameCaptor captor = new FrameCaptor();
        // 必须持有 WebSocket/HttpClient 强引用，否则 GC 会静默断开连接
        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/stats?token=" + token), captor)
                .join();

        String first = captor.frames.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        JsonNode frame = json(first);
        assertThat(frame.path("total").asLong()).isEqualTo(5);
        assertThat(frame.path("pendingTeamCount").asLong()).isEqualTo(1);

        // 审核 REJECT 组1 → pendingTeamCount 1→0，应推送新帧
        putJson("/api/teams/" + team1Id() + "/review", Map.of("action", "REJECT", "reason", "名单有误"));
        String second = captor.frames.poll(5, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        assertThat(json(second).path("pendingTeamCount").asLong()).isZero();
        webSocket.abort();
        client.close();
    }

    @Test
    void websocketRejectsBadToken() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/stats?token=bad-token"),
                        new FrameCaptor())
                .join())
                .isInstanceOf(CompletionException.class);
    }

    @Test
    void exportJoinedWorkbook() throws Exception {
        var resp = getBytes("/api/stats/export?type=JOINED");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<Map<Integer, String>> rows = EasyExcel.read(new ByteArrayInputStream(resp.getBody()))
                .sheet().doReadSync();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get(3)).isEqualTo("组1");
        assertThat(rows.get(0).get(1)).isEqualTo("13800000001");
    }

    @Test
    void exportMissingWorkbook() throws Exception {
        var resp = getBytes("/api/stats/export?type=MISSING");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<Map<Integer, String>> rows = EasyExcel.read(new ByteArrayInputStream(resp.getBody()))
                .sheet().doReadSync();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(1)).isEqualTo("13800000004");
    }

    @Test
    void exportInvalidTypeRejected() {
        var resp = getJson("/api/stats/export?type=BOTH");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void archiveExportsThreeSheetsAndLocks() throws Exception {
        var resp = postBytes("/api/activity/export/archive");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        byte[] bytes = resp.getBody();
        assertThat(EasyExcel.read(new ByteArrayInputStream(bytes)).sheet(0).doReadSync()).hasSize(3);
        assertThat(EasyExcel.read(new ByteArrayInputStream(bytes)).sheet(1).doReadSync()).hasSize(2);
        assertThat(EasyExcel.read(new ByteArrayInputStream(bytes)).sheet(2).doReadSync()).hasSize(3);

        Boolean exported = jdbc.queryForObject("SELECT exported FROM activity LIMIT 1", Boolean.class);
        assertThat(exported).isTrue();
    }

    @Test
    void statsAllZeroWithoutActivity() throws Exception {
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM person");
        jdbc.update("DELETE FROM activity");

        JsonNode stats = json(getJson("/api/stats").getBody());
        assertThat(stats.path("total").asLong()).isZero();
        assertThat(stats.path("joined").asLong()).isZero();
        assertThat(stats.path("notJoined").asLong()).isZero();
        assertThat(stats.path("teamCount").asLong()).isZero();
        assertThat(stats.path("pendingTeamCount").asLong()).isZero();
    }

    static class FrameCaptor implements WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frames.add(data.toString());
            webSocket.request(1); // 覆写后须自行请求下一条，否则只收到首帧
            return null;
        }
    }
}
