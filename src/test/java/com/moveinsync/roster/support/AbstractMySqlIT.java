package com.moveinsync.roster.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(properties = {
        "management.health.rabbit.enabled=false",
        "roster.outbox.scheduling-enabled=false",
        "logging.level.com.moveinsync.roster=DEBUG",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.security=DEBUG"
})
@AutoConfigureMockMvc
public abstract class AbstractMySqlIT {

    protected static final String PASSWORD = "Demo@1234";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--default-time-zone=+00:00", "--innodb-buffer-pool-size=512M")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("rewriteBatchedStatements", "true");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected MvcResult postLogin(String tenantCode, String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new Credentials(tenantCode, username, password));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    protected String tokenFor(String tenantCode, String username) throws Exception {
        MvcResult result = postLogin(tenantCode, username, PASSWORD);
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Login failed with " + result.getResponse().getStatus());
        }
        return json(result).get("accessToken").asText();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Credentials(String tenantCode, String username, String password) {
    }
}
