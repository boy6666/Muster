package com.muster.activity;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报名表单地址拼接：FORM_BASE_URL 末尾带 / 时不得产生双斜杠。
 * （Docker 冒烟发现：FORM_BASE_URL=http://localhost:8090/ 时返回 http://...//form/x）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "muster.form-base-url=http://localhost:5173/")
class ActivityFormUrlIT extends IntegrationTestBase {

    @Test
    void formUrlTrimsTrailingSlashFromBaseUrl() throws com.fasterxml.jackson.core.JsonProcessingException {
        postJson("/api/activity", Map.of(
                "name", "迎新晚会",
                "startTime", LocalDateTime.of(2026, 9, 1, 9, 0).toString(),
                "endTime", LocalDateTime.of(2026, 9, 1, 18, 0).toString(),
                "groupSizeLimit", 5));

        ResponseEntity<String> resp = getJson("/api/activity/form-url");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String url = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(resp.getBody()).path("url").asText();
        // 以 http://host/form/ 开头即排除了 http://host//form/...（scheme 的 // 需排除在检查外）
        assertThat(url).startsWith("http://localhost:5173/form/");
    }
}
