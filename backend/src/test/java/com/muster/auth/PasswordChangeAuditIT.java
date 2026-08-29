package com.muster.auth;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 密码修改属于敏感管理操作，必须落入审计日志（action=PASSWORD_CHANGE）。 */
class PasswordChangeAuditIT extends IntegrationTestBase {

    @Test
    void passwordChangeIsRecordedInOpLog() {
        var changed = putJson("/api/auth/password",
                java.util.Map.of("oldPassword", "admin123", "newPassword", "new-pass-456"));
        assertThat(changed.getStatusCode().value()).isEqualTo(200);

        var logs = getJson("/api/audit/logs?action=PASSWORD_CHANGE&page=1&size=20");
        assertThat(logs.getStatusCode().value()).isEqualTo(200);
        assertThat(logs.getBody()).contains("PASSWORD_CHANGE").contains("admin");
    }
}
