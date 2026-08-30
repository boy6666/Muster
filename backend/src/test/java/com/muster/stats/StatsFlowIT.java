package com.muster.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/**
 * 统计口径：已报名=非草稿组成员；已参加（joined）仅统计通过审核的组；
 * 分组数含草稿；待审核=PENDING 组数。前端首页四卡：已报名/未报名/分组数/待审核。
 */
class StatsFlowIT extends IntegrationTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private String formToken;
    private long team1;
    private long team2;
    private long team3;

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
                List.of("E003", "王五", "13800000003", "体育"),
                List.of("E004", "赵六", "13800000004", "数学"),
                List.of("E005", "钱七", "13800000005", "物理"))));
        formToken = MAPPER.readTree(getJson("/api/activity").getBody()).path("qrToken").asText();

        // 组1(E001,E002) 提交→PENDING；组2(E003) 提交+PASS→CONFIRMED；组3(E004) 仅 DRAFT
        team1 = createDraft("E001", List.of("E001", "E002"));
        postJson("/api/form/" + formToken + "/teams/" + team1 + "/submit", Map.of("leaderPhone", "13800000001"));

        team2 = createDraft("E003", List.of("E003"));
        postJson("/api/form/" + formToken + "/teams/" + team2 + "/submit", Map.of("leaderPhone", "13800000003"));
        putJson("/api/teams/" + team2 + "/review", Map.of("action", "PASS"));

        team3 = createDraft("E004", List.of("E004"));
    }

    private long createDraft(String leader, List<String> members) throws Exception {
        var resp = postJson("/api/form/" + formToken + "/teams",
                Map.of("leaderEmployeeId", leader, "memberEmployeeIdList", members));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return MAPPER.readTree(resp.getBody()).path("id").asLong();
    }

    @Test
    void statsCountsCorrectly() throws Exception {
        var resp = getJson("/api/stats");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode stats = MAPPER.readTree(resp.getBody());
        assertThat(stats.path("total").asLong()).isEqualTo(5);
        assertThat(stats.path("registered").asLong()).isEqualTo(3);
        assertThat(stats.path("notRegistered").asLong()).isEqualTo(2);
        assertThat(stats.path("teamCount").asLong()).isEqualTo(3);
        assertThat(stats.path("pendingTeamCount").asLong()).isEqualTo(1);
    }

    @Test
    void websocketInitialFrameUsesNewFields() throws Exception {
        FrameCaptor captor = new FrameCaptor();
        // 必须持有 WebSocket/HttpClient 强引用，否则 GC 会静默断开连接
        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/stats?token=" + token), captor)
                .join();

        String first = captor.frames.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        JsonNode frame = MAPPER.readTree(first);
        assertThat(frame.path("total").asLong()).isEqualTo(5);
        assertThat(frame.path("registered").asLong()).isEqualTo(3);
        assertThat(frame.path("notRegistered").asLong()).isEqualTo(2);
        assertThat(frame.path("teamCount").asLong()).isEqualTo(3);
        assertThat(frame.path("pendingTeamCount").asLong()).isEqualTo(1);
        webSocket.abort();
        client.close();
    }

    @Test
    void websocketUpdatesOnReviewAndDelete() throws Exception {
        FrameCaptor captor = new FrameCaptor();
        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/stats?token=" + token), captor)
                .join();
        assertThat(captor.frames.poll(5, TimeUnit.SECONDS)).isNotNull(); // 首帧

        // REJECT 组1 → 待审核 1→0；驳回不回落，registered 仍 3
        putJson("/api/teams/" + team1 + "/review", Map.of("action", "REJECT", "reason", "名单有误"));
        String second = captor.frames.poll(5, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        JsonNode frame2 = MAPPER.readTree(second);
        assertThat(frame2.path("pendingTeamCount").asLong()).isZero();
        assertThat(frame2.path("registered").asLong()).isEqualTo(3);

        // 管理员删组2 → registered 3→2
        deleteJson("/api/teams/" + team2);
        String third = captor.frames.poll(5, TimeUnit.SECONDS);
        assertThat(third).isNotNull();
        assertThat(MAPPER.readTree(third).path("registered").asLong()).isEqualTo(2);

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
    void statsAllZeroWithoutActivity() throws Exception {
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM person");
        jdbc.update("DELETE FROM activity");

        JsonNode stats = MAPPER.readTree(getJson("/api/stats").getBody());
        assertThat(stats.path("total").asLong()).isZero();
        assertThat(stats.path("registered").asLong()).isZero();
        assertThat(stats.path("notRegistered").asLong()).isZero();
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
