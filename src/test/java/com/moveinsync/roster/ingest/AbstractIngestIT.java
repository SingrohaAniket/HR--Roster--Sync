package com.moveinsync.roster.ingest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.support.AbstractMySqlIT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

public abstract class AbstractIngestIT extends AbstractMySqlIT {

    protected static final long ACME = 1L;
    protected static final long GLOBEX = 2L;

    @Autowired
    protected JdbcTemplate jdbc;

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 13);
    }

    protected static Map<String, Object> event(String eventId, String externalId, String source, String eventType,
                                               long version, Map<String, Object> payload) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", eventId);
        event.put("employeeExternalId", externalId);
        event.put("source", source);
        event.put("eventType", eventType);
        event.put("version", version);
        event.put("occurredAt", "2026-10-30T02:14:11Z");
        event.put("payload", payload);
        return event;
    }

    protected static Map<String, Object> hr(String externalId, String eventType, long version, Map<String, Object> payload) {
        return event(unique("evt"), externalId, "HR", eventType, version, payload);
    }

    protected static Map<String, Object> newJoiner(String externalId) {
        return hr(externalId, "CREATE", 1, Map.of("name", "Demo Joiner", "email", "joiner@acme.example",
                "phone", "+919000005000", "officeCode", "BLR-WHF", "shiftCode", "S1"));
    }

    protected MockHttpServletResponse send(String token, Object... events) throws Exception {
        return sendList(token, List.of(events));
    }

    protected MockHttpServletResponse sendList(String token, List<?> events) throws Exception {
        return mockMvc.perform(post("/api/v1/roster/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("events", events))))
                .andReturn().getResponse();
    }

    protected String operator() throws Exception {
        return tokenFor("ACME", "acme.operator");
    }

    protected JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    protected String outcome(MockHttpServletResponse response, int index) throws Exception {
        return body(response).get("results").get(index).get("outcome").asText();
    }

    protected Map<String, Object> employee(long tenantId, String externalId) {
        return jdbc.queryForMap("SELECT * FROM employee WHERE tenant_id = ? AND external_id = ?", tenantId, externalId);
    }

    protected int employeeCount(long tenantId, String externalId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = ? AND external_id = ?",
                Integer.class, tenantId, externalId);
    }

    protected int outboxCount(long tenantId, String externalId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE tenant_id = ? AND aggregate_id = ?",
                Integer.class, tenantId, externalId);
    }

    protected List<String> outboxTypes(String externalId) {
        return jdbc.queryForList("SELECT event_type FROM outbox WHERE tenant_id = 1 AND aggregate_id = ? ORDER BY id",
                String.class, externalId);
    }

    protected void restoreSeed() {
        jdbc.update("DELETE FROM booking WHERE id > 8");
        jdbc.update("DELETE FROM trip WHERE id > 5");
        jdbc.update("UPDATE booking SET status = 'BOOKED', cancel_reason = NULL, cancelled_at = NULL WHERE id BETWEEN 1 AND 7");
        jdbc.update("UPDATE booking SET status = 'CANCELLED', cancel_reason = 'DEMO_USER_CANCELLED' WHERE id = 8");
        jdbc.update("UPDATE trip SET seats_taken = CASE id WHEN 1 THEN 2 WHEN 2 THEN 3 WHEN 3 THEN 1 WHEN 4 THEN 1 ELSE 0 END WHERE id BETWEEN 1 AND 5");
        jdbc.update("UPDATE employee SET status = 'ACTIVE', deactivated_at = NULL, hr_version = 5 WHERE id BETWEEN 1 AND 5");
        jdbc.update("DELETE FROM outbox WHERE aggregate_type = 'BOOKING' OR aggregate_id REGEXP '^EMP-100[1-5]$'");
    }

    protected static List<Object> list(Object... items) {
        return new ArrayList<>(List.of(items));
    }
}
