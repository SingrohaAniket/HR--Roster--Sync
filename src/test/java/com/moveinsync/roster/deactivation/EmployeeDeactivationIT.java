package com.moveinsync.roster.deactivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.ingest.AbstractIngestIT;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.web.ResourceNotFoundException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class EmployeeDeactivationIT extends AbstractIngestIT {

    private static final Logger log = LoggerFactory.getLogger("DEACTIVATION-TEST");
    private static final long EMP_1001 = 1L;
    private static final long EMP_1002 = 2L;
    private static final long EMP_1006_ALREADY_INACTIVE = 6L;
    private static final int RACE_ROUNDS = 25;

    @Autowired
    private EmployeeDeactivationService deactivations;

    @BeforeEach
    void seedIsIntact() {
        restoreSeed();
    }

    @AfterEach
    void leaveSeedIntact() {
        restoreSeed();
        TenantContext.clear();
    }

    private boolean deactivate(long employeeId) {
        TenantContext.set(ACME);
        try {
            return deactivations.deactivate(employeeId, DeactivationReason.EMPLOYEE_EXIT, DeactivationTrigger.RECONCILIATION);
        } finally {
            TenantContext.clear();
        }
    }

    private int seats(long tripId) {
        return jdbc.queryForObject("SELECT seats_taken FROM trip WHERE id = ?", Integer.class, tripId);
    }

    private Map<String, Object> booking(long id) {
        return jdbc.queryForMap("SELECT status, cancel_reason, cancelled_at FROM booking WHERE id = ?", id);
    }

    private int outboxRows(String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type = ?", Integer.class, eventType);
    }

    private List<Map<String, Object>> everything() {
        List<Map<String, Object>> all = new ArrayList<>(jdbc.queryForList("SELECT * FROM employee WHERE tenant_id = 1 ORDER BY id"));
        all.addAll(jdbc.queryForList("SELECT * FROM booking ORDER BY id"));
        all.addAll(jdbc.queryForList("SELECT * FROM trip ORDER BY id"));
        all.addAll(jdbc.queryForList("SELECT id, event_type, aggregate_id FROM outbox ORDER BY id"));
        return all;
    }

    @Test
    void onlyFutureBookingsAreCancelledAndThePastBookingStaysBooked() {
        assertTrue(deactivate(EMP_1001));

        assertEquals("CANCELLED", booking(3).get("status"));
        assertEquals("CANCELLED", booking(6).get("status"));
        assertEquals("EMPLOYEE_EXIT", booking(3).get("cancel_reason"));
        assertNotNull(booking(3).get("cancelled_at"));

        assertEquals("BOOKED", booking(1).get("status"), "a past booking is a billing record");
        assertNull(booking(1).get("cancel_reason"));
        assertNull(booking(1).get("cancelled_at"));
        assertEquals(2, seats(1), "the past trip keeps its seat");
    }

    @Test
    void aBookingForTodayCountsAsFuture() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO trip (id, tenant_id, trip_date, shift_code, office_id, total_seats, seats_taken) VALUES (100, 1, ?, 'S1', 1, 2, 1)", today);
        jdbc.update("INSERT INTO booking (id, tenant_id, employee_id, trip_id, trip_date, status) VALUES (100, 1, 1, 100, ?, 'BOOKED')", today);

        deactivate(EMP_1001);

        assertEquals("CANCELLED", booking(100).get("status"));
        assertEquals(0, seats(100));
    }

    @Test
    void otherEmployeesBookingsAreLeftAlone() {
        deactivate(EMP_1001);

        for (long other : List.of(2L, 4L, 5L, 7L)) {
            assertEquals("BOOKED", booking(other).get("status"), "booking " + other);
        }
        assertEquals(1, seats(4), "a trip this employee was not on");
    }

    @Test
    void eachTripLosesExactlyOneSeat() {
        assertEquals(3, seats(2));
        assertEquals(1, seats(3));

        deactivate(EMP_1001);

        assertEquals(2, seats(2));
        assertEquals(0, seats(3));
    }

    @Test
    void theAlreadyCancelledBookingFreesNoSeatAndKeepsItsOriginalReason() {
        Map<String, Object> before = booking(8);
        assertEquals(0, seats(5));

        deactivate(EMP_1001);

        assertEquals(before, booking(8));
        assertEquals("DEMO_USER_CANCELLED", booking(8).get("cancel_reason"));
        assertEquals(0, seats(5));
        assertEquals(2, outboxRows("BOOKING_CANCELLED"), "bookings 3 and 6 only");
    }

    @Test
    void deactivatingTheSeededInactiveEmployeeIsACompleteNoOp() {
        List<Map<String, Object>> before = everything();

        assertFalse(deactivate(EMP_1006_ALREADY_INACTIVE));

        assertEquals(before, everything());
    }

    @Test
    void aSecondDeactivationIsACompleteNoOp() {
        assertTrue(deactivate(EMP_1001));
        List<Map<String, Object>> afterFirst = everything();

        assertFalse(deactivate(EMP_1001));

        assertEquals(afterFirst, everything());
        assertEquals(1, outboxRows("EMPLOYEE_DEACTIVATED"));
        assertEquals(2, outboxRows("BOOKING_CANCELLED"));
    }

    @Test
    void twoThreadsDeactivatingTheSameEmployeeFreeEachSeatOnce() throws Exception {
        for (int round = 1; round <= RACE_ROUNDS; round++) {
            restoreSeed();
            int trip2Before = seats(2);
            int trip3Before = seats(3);

            List<Boolean> results = race(List.of(() -> deactivate(EMP_1001), () -> deactivate(EMP_1001)));

            int trip2After = seats(2);
            int trip3After = seats(3);
            log.info("Round {}: trip 2 seats_taken {} -> {}, trip 3 seats_taken {} -> {}, winners {}, EMPLOYEE_DEACTIVATED rows {}, BOOKING_CANCELLED rows {}",
                    round, trip2Before, trip2After, trip3Before, trip3After, results.stream().filter(won -> won).count(),
                    outboxRows("EMPLOYEE_DEACTIVATED"), outboxRows("BOOKING_CANCELLED"));

            assertEquals(1, results.stream().filter(won -> won).count(), "exactly one caller deactivates, round " + round);
            assertEquals(3, trip2Before);
            assertEquals(1, trip3Before);
            assertEquals(2, trip2After, "round " + round);
            assertEquals(0, trip3After, "round " + round);
            assertEquals(1, outboxRows("EMPLOYEE_DEACTIVATED"), "round " + round);
            assertEquals(2, outboxRows("BOOKING_CANCELLED"), "round " + round);
            assertEquals("BOOKED", booking(1).get("status"));
        }
    }

    @Test
    void twoDeactivateEventsRacingThroughTheApiFreeEachSeatOnce() throws Exception {
        String token = operator();
        for (int round = 1; round <= RACE_ROUNDS; round++) {
            restoreSeed();
            Map<String, Object> first = hr("EMP-1001", "DEACTIVATE", 6, Map.of());
            Map<String, Object> second = hr("EMP-1001", "DEACTIVATE", 7, Map.of());

            List<String> outcomes = race(List.of(() -> outcome(send(token, first), 0), () -> outcome(send(token, second), 0)));

            assertEquals(1, outcomes.stream().filter("APPLIED"::equals).count(), "round " + round + ": " + outcomes);
            assertFalse(outcomes.contains("REJECTED"), "round " + round + ": " + outcomes);
            assertEquals(2, seats(2), "round " + round);
            assertEquals(0, seats(3), "round " + round);
            assertEquals(1, outboxRows("EMPLOYEE_DEACTIVATED"), "round " + round);
        }
    }

    @Test
    void twoEmployeesSharingTripsLeavingTogetherDoNotDeadlock() throws Exception {
        for (int round = 1; round <= RACE_ROUNDS; round++) {
            restoreSeed();
            LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(2);
            jdbc.update("INSERT INTO trip (id, tenant_id, trip_date, shift_code, office_id, total_seats, seats_taken) VALUES (101, 1, ?, 'S1', 1, 4, 2), (102, 1, ?, 'S1', 1, 4, 2)", date, date);
            jdbc.update("INSERT INTO booking (id, tenant_id, employee_id, trip_id, trip_date, status) VALUES (101, 1, 1, 102, ?, 'BOOKED'), (102, 1, 1, 101, ?, 'BOOKED'), (103, 1, 2, 101, ?, 'BOOKED'), (104, 1, 2, 102, ?, 'BOOKED')", date, date, date, date);

            List<Boolean> results = race(List.of(() -> deactivate(EMP_1001), () -> deactivate(EMP_1002)));

            assertEquals(List.of(true, true), results, "round " + round);
            assertEquals(0, seats(101), "round " + round);
            assertEquals(0, seats(102), "round " + round);
            assertEquals(1, seats(2), "both were on trip 2, round " + round);
        }
    }

    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return call.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDeactivateEventThroughTheApiProducesTheFullSideEffects() throws Exception {
        MockHttpServletResponse response = send(operator(), hr("EMP-1001", "DEACTIVATE", 6, Map.of()));

        assertEquals(207, response.getStatus());
        assertEquals("APPLIED", outcome(response, 0));
        assertEquals("INACTIVE", employee(ACME, "EMP-1001").get("status"));
        assertEquals(6L, employee(ACME, "EMP-1001").get("hr_version"));
        assertEquals("CANCELLED", booking(3).get("status"));
        assertEquals("CANCELLED", booking(6).get("status"));
        assertEquals("BOOKED", booking(1).get("status"));
        assertEquals(2, seats(2));
        assertEquals(0, seats(3));
        assertEquals(1, outboxRows("EMPLOYEE_DEACTIVATED"));
        assertEquals(2, outboxRows("BOOKING_CANCELLED"));
    }

    @Test
    void aDeactivateEventForAnAlreadyInactiveEmployeeFreesNothing() throws Exception {
        String token = operator();
        send(token, hr("EMP-1001", "DEACTIVATE", 6, Map.of()));
        List<Map<String, Object>> afterFirst = everything();

        assertEquals("DUPLICATE_NOOP", outcome(send(token, hr("EMP-1001", "DEACTIVATE", 7, Map.of())), 0));

        assertEquals(afterFirst, everything());
    }

    @Test
    void theBookingCancelledPayloadCarriesIdsOnly() throws Exception {
        deactivate(EMP_1001);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, aggregate_type, aggregate_id, status, CAST(payload AS CHAR) AS payload FROM outbox WHERE event_type = 'BOOKING_CANCELLED' ORDER BY aggregate_id");
        assertEquals(2, rows.size());

        Map<String, Object> row = rows.get(0);
        assertEquals("BOOKING", row.get("aggregate_type"));
        assertEquals("3", row.get("aggregate_id"));
        assertEquals("PENDING", row.get("status"));
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        Set<String> keys = new TreeSet<>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("eventId", "eventType", "tenantId", "occurredAt", "employeeExternalId", "changedFields", "hrVersion", "bookingId", "tripId"), keys);
        assertEquals(row.get("id"), payload.get("eventId").asLong());
        assertEquals("BOOKING_CANCELLED", payload.get("eventType").asText());
        assertEquals("EMP-1001", payload.get("employeeExternalId").asText());
        assertEquals(3, payload.get("bookingId").asLong());
        assertEquals(2, payload.get("tripId").asLong());

        for (Map<String, Object> each : rows) {
            for (String personal : List.of("Demo Employee One", "emp1001@acme.example", "+919000001001", "12.97", "77.75", "Demo Pickup Point")) {
                assertFalse(each.get("payload").toString().contains(personal), "the payload contained " + personal);
            }
        }
    }

    @Test
    void theEmployeeDeactivatedPayloadHasNoBookingFields() throws Exception {
        deactivate(EMP_1001);

        JsonNode payload = objectMapper.readTree(jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM outbox WHERE event_type = 'EMPLOYEE_DEACTIVATED'", String.class));

        assertFalse(payload.has("bookingId"));
        assertFalse(payload.has("tripId"));
    }

    @Test
    void anotherTenantCannotDeactivateThisEmployee() {
        List<Map<String, Object>> before = everything();
        TenantContext.set(GLOBEX);

        assertThrows(ResourceNotFoundException.class,
                () -> deactivations.deactivate(EMP_1001, DeactivationReason.EMPLOYEE_EXIT, DeactivationTrigger.INGEST_EVENT));

        TenantContext.clear();
        assertEquals(before, everything());
    }

    @Test
    void aSeatCountThatHadAlreadyDriftedIsReportedAndTheBookingStillCancelled(CapturedOutput output) {
        jdbc.update("UPDATE trip SET seats_taken = 0 WHERE id = 3");

        assertTrue(deactivate(EMP_1001), "the CHECK constraint must not fire, because the guard refuses first");

        assertEquals("CANCELLED", booking(6).get("status"));
        assertEquals(0, seats(3));
        assertTrue(output.getAll().contains("SEAT_COUNT_DRIFT booking 6 on trip 3"));
    }
}
