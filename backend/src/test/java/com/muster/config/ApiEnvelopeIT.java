package com.muster.config;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 框架层 4xx 不应落入兜底 500：缺参→400、未知路径→404、方法不支持→405，
 * 统一错误信封返回，且不刷 ERROR 日志。
 */
class ApiEnvelopeIT extends IntegrationTestBase {

    private String formToken;

    @BeforeEach
    void setupActiveActivity() throws Exception {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.now(clock).minusHours(1).toString(),
                "endTime", LocalDateTime.now(clock).plusHours(5).toString(),
                "groupSizeLimit", 5));
        formToken = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(getJson("/api/activity").getBody()).path("qrToken").asText();
    }

    @Test
    void missingRequestParamReturns400Not500() {
        var resp = getJson("/api/form/" + formToken + "/person");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void unknownApiPathReturns404Not500() {
        var resp = getJson("/api/definitely-not-a-real-endpoint");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }

    @Test
    void unsupportedMethodReturns405Not500() {
        var resp = deleteJson("/api/form/" + formToken);
        assertThat(resp.getStatusCode().value()).isEqualTo(405);
        assertThat(resp.getBody()).contains("METHOD_NOT_ALLOWED");
    }
}
