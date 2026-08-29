package com.muster.smoke;

import com.muster.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest extends IntegrationTestBase {

    @Test
    void healthEndpointIsPublic() {
        var resp = rest.getForEntity("/actuator/health", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }
}
