package com.moveinsync.roster.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.outbox.OutboxWriter;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

class IngestAtomicityIT extends AbstractIngestIT {

    @MockitoSpyBean
    private OutboxWriter outboxWriter;

    @Autowired
    private EntityManager entityManager;

    private OutboxWriter spy() {
        return AopTestUtils.getUltimateTargetObject(outboxWriter);
    }

    private AtomicReference<String> failAfterFlushing(String sql, String externalId) {
        AtomicReference<String> seenInsideTransaction = new AtomicReference<>();
        doAnswer(invocation -> {
            entityManager.flush();
            seenInsideTransaction.set(String.valueOf(entityManager.createNativeQuery(sql).setParameter(1, externalId).getSingleResult()));
            throw new IllegalStateException("Injected failure between the employee write and the commit");
        }).when(spy()).write(any(), any(), any(), any());
        return seenInsideTransaction;
    }

    @Test
    void aFailureAfterUpdatingAnEmployeeRollsBackTheChangeTheLedgerAndTheOutbox() throws Exception {
        String id = unique("ATOMIC");
        String token = operator();
        send(token, newJoiner(id));
        Map<String, Object> before = employee(ACME, id);
        AtomicReference<String> seen = failAfterFlushing("SELECT name FROM employee WHERE external_id = ?1", id);
        Map<String, Object> event = hr(id, "UPDATE", 2, Map.of("name", "Demo Rolled Back"));

        MockHttpServletResponse failed = send(token, event);
        Mockito.reset(spy());

        assertEquals("Demo Rolled Back", seen.get(), "the write must really have reached the database inside the transaction");
        assertEquals(207, failed.getStatus());
        JsonNode result = body(failed).get("results").get(0);
        assertEquals("REJECTED", result.get("outcome").asText());
        assertEquals("PROCESSING_FAILED", result.get("errors").get(0).get("code").asText());
        assertEquals(before, employee(ACME, id), "the employee change did not survive");
        assertEquals(1, outboxCount(ACME, id), "no outbox row survived");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM roster_event WHERE idempotency_key = ?", Integer.class, event.get("eventId")));

        assertEquals("APPLIED", outcome(send(token, event), 0));
        assertEquals("Demo Rolled Back", employee(ACME, id).get("name"));
        assertEquals(2, outboxCount(ACME, id));
    }

    @Test
    void aFailureAfterCreatingAnEmployeeLeavesNoRowAtAll() throws Exception {
        String id = unique("ATOMIC");
        String token = operator();
        AtomicReference<String> seen = failAfterFlushing("SELECT COUNT(*) FROM employee WHERE external_id = ?1", id);

        MockHttpServletResponse failed = send(token, newJoiner(id));
        Mockito.reset(spy());

        assertEquals("1", seen.get(), "the row must really have been inserted inside the transaction");
        assertEquals("REJECTED", outcome(failed, 0));
        assertEquals(0, employeeCount(ACME, id));
        assertEquals(0, outboxCount(ACME, id));
    }

    @Test
    void aFailureWritingABookingEventRollsBackTheWholeDeactivation() throws Exception {
        restoreSeed();
        try {
            String token = operator();
            AtomicInteger calls = new AtomicInteger();
            doAnswer(invocation -> {
                if (calls.incrementAndGet() == 2) {
                    entityManager.flush();
                    throw new IllegalStateException("Injected failure after a seat was released");
                }
                return invocation.callRealMethod();
            }).when(spy()).writeBookingCancelled(any(), any(), any());

            MockHttpServletResponse failed = send(token, hr("EMP-1001", "DEACTIVATE", 6, Map.of()));
            Mockito.reset(spy());

            assertEquals(2, calls.get(), "the failure must really have come after the first seat release");
            assertEquals("REJECTED", outcome(failed, 0));
            assertEquals("ACTIVE", employee(ACME, "EMP-1001").get("status"));
            assertEquals(5L, employee(ACME, "EMP-1001").get("hr_version"));
            assertEquals("BOOKED", jdbc.queryForObject("SELECT status FROM booking WHERE id = 3", String.class));
            assertEquals("BOOKED", jdbc.queryForObject("SELECT status FROM booking WHERE id = 6", String.class));
            assertEquals(3, jdbc.queryForObject("SELECT seats_taken FROM trip WHERE id = 2", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT seats_taken FROM trip WHERE id = 3", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'BOOKING' OR aggregate_id = 'EMP-1001'", Integer.class));
        } finally {
            restoreSeed();
        }
    }

    @Test
    void aFailureOnOneRecordDoesNotDisturbTheOthersInTheBatch() throws Exception {
        String before = unique("ATOMIC");
        String doomed = unique("ATOMIC");
        String after = unique("ATOMIC");
        String token = operator();
        doAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            if (employee.getExternalId().equals(doomed)) {
                throw new IllegalStateException("Injected failure");
            }
            return invocation.callRealMethod();
        }).when(spy()).write(any(), any(), any(), any());

        MockHttpServletResponse response = sendList(token, List.of(newJoiner(before), newJoiner(doomed), newJoiner(after)));
        Mockito.reset(spy());

        assertEquals(207, response.getStatus());
        assertEquals("APPLIED", outcome(response, 0));
        assertEquals("REJECTED", outcome(response, 1));
        assertEquals("APPLIED", outcome(response, 2));
        assertEquals(1, employeeCount(ACME, before));
        assertEquals(0, employeeCount(ACME, doomed));
        assertEquals(1, employeeCount(ACME, after));
        assertTrue(outboxCount(ACME, before) == 1 && outboxCount(ACME, after) == 1 && outboxCount(ACME, doomed) == 0);
    }
}
