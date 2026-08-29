package com.muster.auth;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIT extends IntegrationTestBase {

    @Test
    void protectedEndpointRejectsAnonymous() {
        var resp = rest.getForEntity("/api/activity", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("token").contains("admin");
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "wrong"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    @Test
    void meReturnsUsername() {
        var resp = getJson("/api/auth/me");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"username\":\"admin\"");
    }

    @Test
    void changePasswordThenOldFailsNewWorks() {
        var resp = putJson("/api/auth/password",
                Map.of("oldPassword", "admin123", "newPassword", "newpass456"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        var oldLogin = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), String.class);
        assertThat(oldLogin.getStatusCode().value()).isEqualTo(401);

        var newLogin = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "newpass456"), String.class);
        assertThat(newLogin.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void changePasswordWithWrongOldPasswordFails() {
        var resp = putJson("/api/auth/password",
                Map.of("oldPassword", "bad", "newPassword", "newpass456"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION");
    }
}
