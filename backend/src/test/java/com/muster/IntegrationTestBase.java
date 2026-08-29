package com.muster;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试基座：单例 MySQL 容器 + 每个用例前清库并重置管理员账号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected org.springframework.security.crypto.password.PasswordEncoder encoder;

    protected String token;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM person");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM admin_user");
        jdbc.update("INSERT INTO admin_user(username, password_hash) VALUES('admin', ?)", encoder.encode("admin123"));
    }

    protected HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    protected ResponseEntity<String> postJson(String url, Object body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, authHeaders()), String.class);
    }

    protected ResponseEntity<String> putJson(String url, Object body) {
        return rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, authHeaders()), String.class);
    }

    protected ResponseEntity<String> getJson(String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    protected ResponseEntity<String> deleteJson(String url) {
        return rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(authHeaders()), String.class);
    }
}
