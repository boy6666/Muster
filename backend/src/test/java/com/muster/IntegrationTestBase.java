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

import java.util.List;
import java.util.Map;

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

    @Autowired
    protected java.time.Clock clock;

    protected String token;

    @BeforeEach
    void resetDatabase() throws com.fasterxml.jackson.core.JsonProcessingException {
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM person");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM admin_user");
        jdbc.update("INSERT INTO admin_user(username, password_hash) VALUES('admin', ?)", encoder.encode("admin123"));
        loginAsAdmin();
    }

    /** 每个用例默认以 admin 登录，填充 token；需要匿名场景的用例可先置回 null。 */
    protected void loginAsAdmin() throws com.fasterxml.jackson.core.JsonProcessingException {
        var resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), String.class);
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(resp.getBody()).path("token").asText(null);
        } else {
            token = null;
        }
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

    /** 生成三列（姓名/手机号/部门）花名册 xlsx。 */
    protected byte[] rosterWorkbook(List<List<Object>> rows) {
        var head = List.of(List.of("姓名"), List.of("手机号"), List.of("部门"));
        var out = new java.io.ByteArrayOutputStream();
        com.alibaba.excel.EasyExcel.write(out).head(head).sheet("花名册").doWrite(rows);
        return out.toByteArray();
    }

    protected ResponseEntity<String> uploadRoster(byte[] bytes) {
        var resource = new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "roster.xlsx";
            }
        };
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", resource);
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/api/roster/import", new HttpEntity<>(body, headers), String.class);
    }
}
