package com.muster.config;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerIT extends IntegrationTestBase {

    @Test
    void malformedJsonBodyReturnsValidation() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        var resp = rest.exchange("/api/activity", HttpMethod.POST, new HttpEntity<>("not-json", headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void wrongDatetimeFormatReturnsValidation() {
        var resp = postJson("/api/activity", java.util.Map.of(
                "name", "迎新晚会",
                "startTime", "2026/08/29 10:00",
                "endTime", "2026-08-29T12:00:00",
                "groupSizeLimit", 5));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void nonExcelRosterUploadReturnsValidation() {
        // 导入接口先校验「当前活动」，须先建活动才能触达文件解析逻辑
        postJson("/api/activity", java.util.Map.of(
                "name", "迎新晚会",
                "startTime", "2026-08-29T10:00:00",
                "endTime", "2026-08-29T12:00:00",
                "groupSizeLimit", 5));
        var resource = new org.springframework.core.io.ByteArrayResource("这不是Excel文件".getBytes()) {
            @Override
            public String getFilename() {
                return "roster.txt";
            }
        };
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", resource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        var resp = rest.exchange("/api/roster/import", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }

    @Test
    void oversizedUploadReturns413() {
        var resource = new org.springframework.core.io.ByteArrayResource(new byte[10 * 1024 * 1024 + 1]) {
            @Override
            public String getFilename() {
                return "big.xlsx";
            }
        };
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", resource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        var resp = rest.exchange("/api/roster/import", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(413);
        assertThat(resp.getBody()).contains("PAYLOAD_TOO_LARGE");
    }
}
