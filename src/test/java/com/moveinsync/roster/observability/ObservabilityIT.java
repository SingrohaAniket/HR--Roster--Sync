package com.moveinsync.roster.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.deactivation.DeactivationTrigger;
import com.moveinsync.roster.domain.EventOutcome;
import com.moveinsync.roster.ingest.AbstractIngestIT;
import com.moveinsync.roster.outbox.OutboxRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends AbstractIngestIT {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private OutboxRepository outbox;

    @BeforeEach
    void emptyOutbox() {
        jdbc.update("UPDATE outbox SET status = 'PUBLISHED' WHERE status <> 'PUBLISHED'");
    }

    private double events(EventOutcome outcome) {
        return registry.get("roster.ingest.events").tag("outcome", outcome.name()).counter().count();
    }

    private double rejections(ErrorCode code) {
        return registry.get("roster.ingest.rejections").tag("code", code.name()).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void ingestingABatchMovesTheCountersWithTheRightTags() throws Exception {
        double applied = events(EventOutcome.APPLIED);
        double stale = events(EventOutcome.IGNORED_STALE);
        double rejected = events(EventOutcome.REJECTED);
        double badPhone = rejections(ErrorCode.INVALID_PHONE);
        double unknownOffice = rejections(ErrorCode.UNKNOWN_OFFICE);
        long batches = registry.get("roster.ingest.batch").timer().count();
        String id = unique("METRIC");

        MockHttpServletResponse response = send(operator(),
                hr(id, "UPDATE", 7, Map.of("name", "Demo")),
                hr(id, "UPDATE", 6, Map.of("name", "Older")),
                hr(id, "UPDATE", 8, Map.of("phone", "12345")),
                hr(id, "UPDATE", 9, Map.of("officeCode", "NO-SUCH-OFFICE")));

        assertEquals(207, response.getStatus());
        assertEquals(applied + 1, events(EventOutcome.APPLIED));
        assertEquals(stale + 1, events(EventOutcome.IGNORED_STALE));
        assertEquals(rejected + 2, events(EventOutcome.REJECTED));
        assertEquals(badPhone + 1, rejections(ErrorCode.INVALID_PHONE));
        assertEquals(unknownOffice + 1, rejections(ErrorCode.UNKNOWN_OFFICE));
        assertEquals(batches + 1, registry.get("roster.ingest.batch").timer().count());
    }

    @Test
    void theBatchTimerPublishesP50AndP99() {
        Set<String> phis = registry.find("roster.ingest.batch.percentile").gauges().stream()
                .map(gauge -> gauge.getId().getTag("phi")).collect(Collectors.toSet());

        assertEquals(Set.of("0.5", "0.99"), phis);
    }

    @Test
    void lagIsZeroWithAnEmptyOutboxAndARealAgeWithAPendingRow() {
        assertEquals(0.0, gauge("roster.outbox.lag.seconds"), "zero, not an error");
        assertEquals(0.0, gauge("roster.outbox.pending"));

        jdbc.update("INSERT INTO outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at) VALUES (1, 'EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '{}', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 120 SECOND))", unique("LAG"));

        double lag = gauge("roster.outbox.lag.seconds");
        assertTrue(lag >= 119 && lag < 300, "the age of the oldest pending row, got " + lag);
        assertEquals(1.0, gauge("roster.outbox.pending"));
    }

    @Test
    void lagFollowsTheOldestRow() {
        jdbc.update("INSERT INTO outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at) VALUES (1, 'EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '{}', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 600 SECOND))", unique("LAG"));
        jdbc.update("INSERT INTO outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at) VALUES (1, 'EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '{}', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 10 SECOND))", unique("LAG"));

        assertTrue(gauge("roster.outbox.lag.seconds") >= 599);
    }

    @Test
    void thePendingGaugeIsCorrectAfterARestartBecauseItReadsTheDatabase() throws Exception {
        send(operator(), hr(unique("RESTART"), "UPDATE", 1, Map.of("name", "Demo")), hr(unique("RESTART"), "UPDATE", 1, Map.of("name", "Demo")));
        assertEquals(2.0, gauge("roster.outbox.pending"));

        SimpleMeterRegistry afterRestart = new SimpleMeterRegistry();
        RosterMetrics counters = new RosterMetrics(afterRestart);
        new DatabaseGauges(outbox, jdbc).bindTo(afterRestart);

        assertEquals(0.0, afterRestart.get("roster.outbox.published").counter().count(), "a counter does start again from zero");
        assertNotNull(counters);
        assertEquals(2.0, afterRestart.get("roster.outbox.pending").gauge().value(), "the backlog is still there and the gauge says so");
        assertTrue(afterRestart.get("roster.outbox.lag.seconds").gauge().value() >= 0);
    }

    private static final Set<String> CLOSED_TAG_KEYS = Set.of("outcome", "code", "trigger", "phi", "application");

    private List<Meter> rosterMeters() {
        return registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("roster.")).toList();
    }

    @Test
    void noRosterMetricCarriesAnUnboundedTag() throws Exception {
        send(operator(), hr(unique("TAGS"), "UPDATE", 1, Map.of("name", "Demo")), hr(unique("TAGS"), "UPDATE", 0, Map.of()));

        Set<String> outcomes = Arrays.stream(EventOutcome.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> triggers = Arrays.stream(DeactivationTrigger.values()).map(Enum::name).collect(Collectors.toSet());
        for (Meter meter : rosterMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertTrue(CLOSED_TAG_KEYS.contains(tag.getKey()), meter.getId() + " has the tag " + tag.getKey());
                if (tag.getKey().equals("outcome")) {
                    assertTrue(outcomes.contains(tag.getValue()), meter.getId().toString());
                } else if (tag.getKey().equals("code")) {
                    assertTrue(codes.contains(tag.getValue()), meter.getId().toString());
                } else if (tag.getKey().equals("trigger")) {
                    assertTrue(triggers.contains(tag.getValue()), meter.getId().toString());
                }
            }
        }
    }

    @Test
    void newIdsDoNotCreateNewTimeSeries() throws Exception {
        send(operator(), hr(unique("WARM"), "UPDATE", 1, Map.of("name", "Demo")));
        int before = rosterMeters().size();

        for (int i = 0; i < 20; i++) {
            send(operator(), hr(unique("SERIES"), "UPDATE", 1, Map.of("name", "Demo")));
        }

        assertEquals(before, rosterMeters().size(), "the number of series must not grow with the number of employees");
    }

    @Test
    void everyMetricTheBriefNamesExists() {
        for (String name : List.of("roster.outbox.pending", "roster.outbox.lag.seconds", "roster.outbox.published", "roster.outbox.failed",
                "roster.outbox.retries", "roster.ingest.events", "roster.ingest.rejections", "roster.ingest.batch",
                "roster.reconciliation.duration", "roster.reconciliation.missing.percent", "roster.reconciliation.safety_brake.trips",
                "roster.deactivations")) {
            assertNotNull(registry.find(name).meter(), name);
        }
    }

    private static String responseTraceId(MockHttpServletResponse response) {
        return response.getHeader("X-Request-Id");
    }

    @Test
    void aRequestWithoutAnIdGetsAGeneratedOneInTheLogAndTheResponse(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();

        String traceId = responseTraceId(response);
        assertNotNull(traceId);
        UUID.fromString(traceId);
        assertTrue(output.getAll().contains("[" + traceId + "]"), "the id must be on the request's log lines");
    }

    @Test
    void aRequestWithAnIdReusesIt(CapturedOutput output) throws Exception {
        String inbound = "req-" + UUID.randomUUID();

        MockHttpServletResponse response = mockMvc.perform(get("/actuator/health").header("X-Request-Id", inbound)).andReturn().getResponse();

        assertEquals(inbound, responseTraceId(response));
        assertTrue(output.getAll().contains("[" + inbound + "]"));
    }

    @Test
    void anUnsafeInboundIdIsReplacedSoItCannotForgeALogLine(CapturedOutput output) throws Exception {
        for (String unsafe : List.of("forged line\nINFO fake", "has spaces", "x".repeat(65), "emp@acme.example")) {
            MockHttpServletResponse response = mockMvc.perform(get("/actuator/health").header("X-Request-Id", unsafe)).andReturn().getResponse();

            assertFalse(unsafe.equals(responseTraceId(response)), unsafe);
            UUID.fromString(responseTraceId(response));
        }
        assertFalse(output.getAll().contains("emp@acme.example"));
    }

    @Test
    void aFailedAuthenticationIsTracedToo() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/employees/me")).andReturn().getResponse();

        assertEquals(401, response.getStatus());
        assertNotNull(responseTraceId(response));
    }

    @Test
    void tenantIdDoesNotLeakToTheNextRequestOnTheSameThread(CapturedOutput output) throws Exception {
        String authenticatedId = "tenant-" + UUID.randomUUID();
        String anonymousId = "anon-" + UUID.randomUUID();
        String token = tokenFor("GLOBEX", "globex.admin");

        mockMvc.perform(get("/api/v1/employees/GLX-2001").header("Authorization", "Bearer " + token).header("X-Request-Id", authenticatedId)).andReturn();
        assertNull(MDC.get("tenantId"), "cleared as the request ended");
        assertNull(MDC.get("traceId"));

        mockMvc.perform(get("/actuator/health").header("X-Request-Id", anonymousId)).andReturn();

        String log = output.getAll();
        assertTrue(log.contains("[" + authenticatedId + "] [2]"), "the authenticated request's lines carry its tenant");
        assertFalse(log.contains("[" + anonymousId + "] [2]"), "the next request on this thread must not inherit it");
        assertTrue(log.contains("[" + anonymousId + "] []"));
    }

    @Test
    void aDuplicateEventDoesNotLogTheDatabaseErrorBecauseItIsANormalOutcome(CapturedOutput output) throws Exception {
        Map<String, Object> event = hr(unique("QUIET"), "UPDATE", 1, Map.of("name", "Demo"));
        String token = operator();
        send(token, event);

        assertEquals("DUPLICATE_NOOP", outcome(send(token, event), 0));

        assertFalse(output.getAll().contains("Duplicate entry"), "a raw database message reached the log");
    }

    @Test
    void personalDataStillDoesNotReachTheLogWithTheNewFields(CapturedOutput output) throws Exception {
        String marker = "PiiMarker" + UUID.randomUUID().toString().replace("-", "");

        send(operator(), hr(unique("PII"), "UPDATE", 1, Map.of("name", marker, "email", "pii.check@acme.example")));

        assertFalse(output.getAll().contains(marker));
        assertFalse(output.getAll().contains("pii.check@acme.example"));
    }
}
