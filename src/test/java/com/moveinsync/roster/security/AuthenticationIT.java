package com.moveinsync.roster.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.config.JwtProperties;
import com.moveinsync.roster.domain.AppUser;
import com.moveinsync.roster.domain.Role;
import com.moveinsync.roster.support.AbstractMySqlIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticationIT extends AbstractMySqlIT {

    private static final String ME = "/api/v1/employees/me";

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private MockHttpServletResponse getMe(String token) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    @ParameterizedTest
    @CsvSource({
            "ACME,   acme.employee,   EMPLOYEE",
            "ACME,   acme.admin,      ADMIN",
            "ACME,   acme.operator,   OPERATOR",
            "GLOBEX, globex.employee, EMPLOYEE",
            "GLOBEX, globex.admin,    ADMIN",
            "GLOBEX, globex.operator, OPERATOR"
    })
    void everySeededUserCanLogIn(String tenantCode, String username, String role) throws Exception {
        MvcResult result = postLogin(tenantCode, username, PASSWORD);

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertFalse(body.get("accessToken").asText().isBlank());
        assertEquals("Bearer", body.get("tokenType").asText());
        assertEquals(3600, body.get("expiresInSeconds").asLong());
        assertEquals(role, body.get("role").asText());
    }

    @Test
    void wrongPasswordBodyIsByteIdenticalToEveryOtherFailure() throws Exception {
        users.save(new AppUser(1L, "acme.disabled", passwordEncoder.encode(PASSWORD), Role.ADMIN, null));
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE username = 'acme.disabled'");

        MockHttpServletResponse wrongPassword = postLogin("ACME", "acme.admin", "wrong").getResponse();
        MockHttpServletResponse unknownUser = postLogin("ACME", "nobody", PASSWORD).getResponse();
        MockHttpServletResponse unknownTenant = postLogin("NOPE", "acme.admin", PASSWORD).getResponse();
        MockHttpServletResponse disabledUser = postLogin("ACME", "acme.disabled", PASSWORD).getResponse();
        MockHttpServletResponse otherTenantsUser = postLogin("GLOBEX", "acme.admin", PASSWORD).getResponse();

        assertEquals(401, wrongPassword.getStatus());
        assertEquals("AUTHENTICATION_FAILED", objectMapper.readTree(wrongPassword.getContentAsString()).get("code").asText());
        for (MockHttpServletResponse other : new MockHttpServletResponse[] {unknownUser, unknownTenant, disabledUser, otherTenantsUser}) {
            assertEquals(401, other.getStatus());
            assertArrayEquals(wrongPassword.getContentAsByteArray(), other.getContentAsByteArray());
            assertEquals(wrongPassword.getContentType(), other.getContentType());
        }
    }

    @Test
    void anInactiveTenantCannotLogIn() throws Exception {
        jdbc.update("INSERT INTO tenant (id, code, name, active) VALUES (99, 'CLOSED', 'Closed Demo Corp', FALSE)");
        users.save(new AppUser(99L, "closed.admin", passwordEncoder.encode(PASSWORD), Role.ADMIN, null));

        assertEquals(401, postLogin("CLOSED", "closed.admin", PASSWORD).getResponse().getStatus());
    }

    @Test
    void aVeryLongPasswordIsAnOrdinaryFailure() throws Exception {
        assertEquals(401, postLogin("ACME", "acme.admin", "x".repeat(10_000)).getResponse().getStatus());
    }

    @Test
    void aMissingUserTakesAsLongAsAWrongPassword() throws Exception {
        postLogin("ACME", "acme.admin", "warm-up");

        double wrongPassword = averageMillis(() -> postLogin("ACME", "acme.admin", "wrong"));
        double unknownUser = averageMillis(() -> postLogin("ACME", "nobody", "wrong"));
        double unknownTenant = averageMillis(() -> postLogin("NOPE", "nobody", "wrong"));

        assertTrue(unknownUser > wrongPassword / 2, "unknown user " + unknownUser + " ms vs " + wrongPassword + " ms");
        assertTrue(unknownTenant > wrongPassword / 2, "unknown tenant " + unknownTenant + " ms vs " + wrongPassword + " ms");
    }

    private static double averageMillis(Callable<?> call) throws Exception {
        int runs = 5;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            call.call();
        }
        return (System.nanoTime() - start) / 1_000_000.0 / runs;
    }

    @Test
    void noAuthorizationHeaderIs401AndNotARedirect() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(ME)).andReturn().getResponse();

        assertEquals(401, response.getStatus());
        assertNull(response.getRedirectedUrl());
        assertNull(response.getForwardedUrl());
        assertNull(response.getHeader("Location"));
    }

    @Test
    void tamperedSignatureIs401() throws Exception {
        String token = tokenFor("ACME", "acme.employee");
        char last = token.charAt(token.length() - 2);

        String tampered = token.substring(0, token.length() - 2) + (last == 'A' ? 'B' : 'A') + token.charAt(token.length() - 1);

        assertEquals(401, getMe(tampered).getStatus());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        String expired = Jwts.builder()
                .subject("acme.employee")
                .claim("tenantId", 1L)
                .claim("tenantCode", "ACME")
                .claim("role", "EMPLOYEE")
                .claim("employeeExternalId", "EMP-1002")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertEquals(401, getMe(expired).getStatus());
    }

    @Test
    void garbageTokenIs401AndNotAnError() throws Exception {
        assertEquals(401, getMe("not.a.token").getStatus());
    }

    @Test
    void aStaleAuthorizationHeaderDoesNotBlockLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .header("Authorization", "Bearer stale.token.value")
                .contentType("application/json")
                .content("{\"tenantCode\":\"ACME\",\"username\":\"acme.admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    void healthAndInfoAreOpen() throws Exception {
        assertEquals(200, mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus());
    }

    @Test
    void prometheusNeedsAToken() throws Exception {
        assertEquals(401, mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus());
    }

    @Test
    void onlyAnOperatorCanReadMetricsBecauseTheyCombineEveryTenant() throws Exception {
        for (String user : List.of("acme.employee", "acme.admin")) {
            String token = tokenFor("ACME", user);
            for (String path : List.of("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/roster.outbox.pending")) {
                MockHttpServletResponse response = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse();
                assertEquals(403, response.getStatus(), user + " " + path);
                assertEquals("ACCESS_DENIED", objectMapper.readTree(response.getContentAsString()).get("code").asText());
            }
        }

        String operator = tokenFor("ACME", "acme.operator");
        assertEquals(200, mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + operator)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus());
    }

    @Test
    void healthStaysOpenForAnyoneBecauseDockerComposeGatesOnIt() throws Exception {
        String employee = tokenFor("ACME", "acme.employee");

        assertEquals(200, mockMvc.perform(get("/actuator/health").header("Authorization", "Bearer " + employee)).andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus());
    }

    @Test
    void noTokenPasswordOrHashIsLoggedEvenAtDebug(CapturedOutput output) throws Exception {
        String wrongPassword = "Wr0ng-Password-Marker";
        String employeeToken = tokenFor("ACME", "acme.employee");
        postLogin("ACME", "acme.admin", wrongPassword);
        getMe(employeeToken);
        getMe(employeeToken + "x");

        String log = output.getAll();
        assertTrue(log.contains("EmployeeSelfView[externalId=EMP-1002]"), "DEBUG lines must really be on for this test to mean anything");
        assertFalse(log.contains("eyJ"), "a token was logged");
        assertFalse(log.contains(PASSWORD), "the password was logged");
        assertFalse(log.contains(wrongPassword), "a rejected password was logged");
        assertFalse(log.contains("$2a$10$"), "a password hash was logged");
    }
}
