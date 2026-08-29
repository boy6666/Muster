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
}
