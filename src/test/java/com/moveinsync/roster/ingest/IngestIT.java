package com.moveinsync.roster.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.core.hashing.BucketAssigner;
import com.moveinsync.roster.core.hashing.ContentHasher;
import com.moveinsync.roster.deactivation.DeactivationReason;
import com.moveinsync.roster.deactivation.DeactivationTrigger;
import com.moveinsync.roster.deactivation.EmployeeDeactivationService;
import com.moveinsync.roster.security.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class IngestIT extends AbstractIngestIT {

    @Autowired
    private EmployeeDeactivationService deactivations;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private List<Map<String, Object>> allEmployees() {
        return jdbc.queryForList("SELECT * FROM employee ORDER BY id");
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void replayingTheEntireFeedTwiceChangesNothing() throws Exception {
        String a = unique("REPLAY-A");
        String b = unique("REPLAY-B");
        String c = unique("REPLAY-C");
        List<Object> feed = list(
                newJoiner(a),
                hr(a, "UPDATE", 7, Map.of("officeCode", "BLR-ECO", "shiftCode", "S2")),
                hr(a, "UPDATE", 6, Map.of("shiftCode", "S9")),
                event(unique("evt"), a, "TRANSPORT", "UPDATE", 3, Map.of("pickupLabel", "Demo Gate", "pickupLat", 12.5, "pickupLng", 77.5)),
                newJoiner(b),
                hr(b, "DEACTIVATE", 2, Map.of()),
                hr(c, "UPDATE", 4, Map.of("name", "Demo Upserted")),
                hr(c, "UPDATE", 5, Map.of("phone", "not-a-phone")),
                hr(c, "UPDATE", 6, Map.of("officeCode", "NO-SUCH-OFFICE")),
                hr(c, "UPDATE", 7, Map.of("pickupLabel", "Only Unowned")));
        String token = operator();

        MockHttpServletResponse first = sendList(token, feed);
        assertEquals(207, first.getStatus());
        assertEquals(6, body(first).get("applied").asInt());
        List<Map<String, Object>> employeesBefore = allEmployees();
        int outboxBefore = rowCount("outbox");
        int ledgerBefore = rowCount("roster_event");

        MockHttpServletResponse second = sendList(token, feed);

        assertEquals(207, second.getStatus());
        assertEquals(0, body(second).get("applied").asInt());
        for (JsonNode result : body(second).get("results")) {
            assertTrue(Set.of("DUPLICATE_NOOP", "REJECTED").contains(result.get("outcome").asText()), result.toString());
        }
        assertEquals(employeesBefore, allEmployees());
        assertEquals(outboxBefore, rowCount("outbox"));
        assertEquals(ledgerBefore, rowCount("roster_event"));
    }

    @Test
    void duplicateEventIdIsANoOpAndWritesExactlyOneOutboxRow() throws Exception {
        String id = unique("DUP");
        Map<String, Object> event = newJoiner(id);
        String token = operator();

        assertEquals("APPLIED", outcome(send(token, event), 0));
        MockHttpServletResponse replay = send(token, event);

        JsonNode result = body(replay).get("results").get(0);
        assertEquals("DUPLICATE_NOOP", result.get("outcome").asText());
        assertEquals("APPLIED", result.get("originalOutcome").asText());
        assertEquals(0, result.get("warnings").size());
        assertEquals(1, outboxCount(ACME, id));
    }

    @Test
    void aRepeatedEventIdWithADifferentPayloadKeepsTheFirstOutcomeAndWarns() throws Exception {
        String id = unique("REUSE");
        String eventId = unique("evt");
        String token = operator();
        send(token, event(eventId, id, "HR", "UPDATE", 3, Map.of("name", "First Name")));

        MockHttpServletResponse second = send(token, event(eventId, id, "HR", "UPDATE", 4, Map.of("name", "Second Name")));

        JsonNode result = body(second).get("results").get(0);
        assertEquals("DUPLICATE_NOOP", result.get("outcome").asText());
        assertEquals("IDEMPOTENCY_KEY_REUSED", result.get("warnings").get(0).get("code").asText());
        assertEquals("First Name", employee(ACME, id).get("name"));
        assertEquals(3L, employee(ACME, id).get("hr_version"));
    }

    @Test
    void exampleUseCase1OfficeTransferV7ArrivesBeforeShiftChangeV6() throws Exception {
        String id = unique("UC1");
        String token = operator();
        send(token, newJoiner(id));

        MockHttpServletResponse transfer = send(token, hr(id, "UPDATE", 7, Map.of("officeCode", "BLR-ECO", "shiftCode", "S2")));
        MockHttpServletResponse shiftChange = send(token, hr(id, "UPDATE", 6, Map.of("shiftCode", "S9")));

        assertEquals("APPLIED", outcome(transfer, 0));
        assertEquals("IGNORED_STALE", outcome(shiftChange, 0));
        Map<String, Object> row = employee(ACME, id);
        assertEquals(2L, row.get("office_id"), "the new office, BLR-ECO");
        assertEquals("S2", row.get("shift_code"), "the version 7 shift");
        assertEquals(7L, row.get("hr_version"));
        assertEquals(List.of("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED"), outboxTypes(id), "a stale event emits nothing");
    }

    @Test
    void equalVersionWithANewEventIdIsANoOp() throws Exception {
        String id = unique("EQ");
        String token = operator();
        send(token, hr(id, "UPDATE", 5, Map.of("name", "Original")));
        Map<String, Object> before = employee(ACME, id);

        MockHttpServletResponse same = send(token, hr(id, "UPDATE", 5, Map.of("name", "Changed")));

        assertEquals("DUPLICATE_NOOP", outcome(same, 0));
        assertEquals(before, employee(ACME, id));
        assertEquals(1, outboxCount(ACME, id));
    }

    @Test
    void oneBadRecordInABatchOf100Gives207With99AppliedAnd1Rejected() throws Exception {
        List<Object> batch = new ArrayList<>();
        String prefix = unique("BATCH");
        for (int i = 0; i < 100; i++) {
            Map<String, Object> event = newJoiner(prefix + "-" + i);
            if (i == 41) {
                event.put("payload", Map.of("name", "Demo", "email", "bad@acme.example", "phone", "+9198765", "officeCode", "BLR-WHF"));
            }
            batch.add(event);
        }

        MockHttpServletResponse response = sendList(operator(), batch);

        assertEquals(207, response.getStatus());
        JsonNode body = body(response);
        assertEquals(100, body.get("received").asInt());
        assertEquals(99, body.get("applied").asInt());
        assertEquals(1, body.get("rejected").asInt());
        JsonNode bad = body.get("results").get(41);
        assertEquals("REJECTED", bad.get("outcome").asText());
        assertEquals("INVALID_PHONE", bad.get("errors").get(0).get("code").asText());
        assertEquals("phone", bad.get("errors").get(0).get("field").asText());
        assertEquals(0, employeeCount(ACME, prefix + "-41"));
        assertEquals(1, employeeCount(ACME, prefix + "-40"));
        assertEquals(1, employeeCount(ACME, prefix + "-42"));
        assertFalse(response.getContentAsString().contains("+9198765"), "the rejected value must not be echoed");
    }

    @Test
    void malformedPhoneIsRejectedAndTheBatchSurvives() throws Exception {
        String good = unique("GOOD");
        String bad = unique("BAD");

        MockHttpServletResponse response = send(operator(), hr(bad, "UPDATE", 2, Map.of("phone", "12345")), newJoiner(good));

        assertEquals(207, response.getStatus());
        assertEquals("INVALID_PHONE", body(response).get("results").get(0).get("errors").get(0).get("code").asText());
        assertEquals("APPLIED", outcome(response, 1));
    }

    @Test
    void unknownOfficeCodeIsRejected() throws Exception {
        String id = unique("OFFICE");

        JsonNode result = body(send(operator(), hr(id, "UPDATE", 2, Map.of("officeCode", "NO-SUCH-OFFICE")))).get("results").get(0);

        assertEquals("REJECTED", result.get("outcome").asText());
        assertEquals("UNKNOWN_OFFICE", result.get("errors").get(0).get("code").asText());
        assertEquals("officeCode", result.get("errors").get(0).get("field").asText());
        assertEquals(0, employeeCount(ACME, id));
    }

    @Test
    void anotherTenantsOfficeCodeIsUnknown() throws Exception {
        assertEquals("REJECTED", outcome(send(operator(), hr(unique("OFFICE"), "UPDATE", 2, Map.of("officeCode", "DEL-CYB"))), 0));
    }

    @Test
    void anInactiveOfficeIsRejected() throws Exception {
        jdbc.update("INSERT IGNORE INTO office (tenant_id, code, name, city, active) VALUES (1, 'CLOSED-OFC', 'Closed', 'Nowhere', FALSE)");

        assertEquals("REJECTED", outcome(send(operator(), hr(unique("OFFICE"), "UPDATE", 2, Map.of("officeCode", "CLOSED-OFC"))), 0));
    }

    @Test
    void anUnknownOfficeOnATransportEventIsDroppedNotRejected() throws Exception {
        String id = unique("TOFFICE");

        JsonNode result = body(send(operator(), event(unique("evt"), id, "TRANSPORT", "UPDATE", 1,
                Map.of("officeCode", "NO-SUCH-OFFICE", "pickupLabel", "Demo Gate")))).get("results").get(0);

        assertEquals("APPLIED", result.get("outcome").asText());
        assertEquals("officeCode", result.get("warnings").get(0).get("field").asText());
    }

    @Test
    void hrEventCarryingAPickupPointLeavesItUnchangedAndWarns() throws Exception {
        String id = unique("OWN");
        String token = operator();
        send(token, newJoiner(id));
        send(token, event(unique("evt"), id, "TRANSPORT", "UPDATE", 1, Map.of("pickupLat", 12.5, "pickupLng", 77.5, "pickupLabel", "Demo Gate")));

        JsonNode result = body(send(token, hr(id, "UPDATE", 2, Map.of("name", "Demo Renamed",
                "pickupLat", 1.0, "pickupLng", 2.0, "pickupLabel", "Overwritten")))).get("results").get(0);

        assertEquals("APPLIED", result.get("outcome").asText());
        assertEquals(0, result.get("errors").size());
        Set<String> warned = new TreeSet<>();
        result.get("warnings").forEach(warning -> {
            assertEquals("FIELD_IGNORED_NOT_OWNED", warning.get("code").asText());
            warned.add(warning.get("field").asText());
        });
        assertEquals(Set.of("pickupLat", "pickupLng", "pickupLabel"), warned);
        Map<String, Object> row = employee(ACME, id);
        assertEquals("Demo Renamed", row.get("name"));
        assertEquals("Demo Gate", row.get("pickup_label"));
        assertEquals(0, new BigDecimal("12.5").compareTo((BigDecimal) row.get("pickup_lat")));
        assertEquals(1L, row.get("transport_version"), "an HR event never moves the transport counter");
    }

    @Test
    void transportEventCarryingANameLeavesItUnchangedAndWarns() throws Exception {
        String id = unique("OWN");
        String token = operator();
        send(token, newJoiner(id));
        Map<String, Object> before = employee(ACME, id);

        JsonNode result = body(send(token, event(unique("evt"), id, "TRANSPORT", "UPDATE", 9,
                Map.of("name", "Overwritten", "pickupLabel", "Demo Gate")))).get("results").get(0);

        assertEquals("APPLIED", result.get("outcome").asText());
        assertEquals("FIELD_IGNORED_NOT_OWNED", result.get("warnings").get(0).get("code").asText());
        assertEquals("name", result.get("warnings").get(0).get("field").asText());
        Map<String, Object> row = employee(ACME, id);
        assertEquals("Demo Joiner", row.get("name"));
        assertEquals("Demo Gate", row.get("pickup_label"));
        assertEquals(1L, row.get("hr_version"), "a transport event never moves the HR counter");
        assertEquals(before.get("hr_updated_at"), row.get("hr_updated_at"));
        assertEquals(before.get("content_hash"), row.get("content_hash"), "the hash covers HR fields only");
        assertEquals(9L, row.get("transport_version"));
        assertNotNull(row.get("transport_updated_at"));
    }

    @Test
    void aTransportVersionBelowTheHrVersionIsNotStale() throws Exception {
        String id = unique("DOMAIN");
        String token = operator();
        send(token, hr(id, "UPDATE", 9, Map.of("name", "Demo")));

        assertEquals("APPLIED", outcome(send(token, event(unique("evt"), id, "TRANSPORT", "UPDATE", 3, Map.of("pickupLabel", "Demo Gate"))), 0));
    }

    @Test
    void payloadWithOnlyUnownedFieldsIsIgnoredAndAdvancesNothing() throws Exception {
        String id = unique("UNOWNED");
        String token = operator();
        send(token, newJoiner(id));
        Map<String, Object> before = employee(ACME, id);

        JsonNode result = body(send(token, hr(id, "UPDATE", 5, Map.of("pickupLabel", "Only Unowned")))).get("results").get(0);

        assertEquals("IGNORED_NO_OWNED_FIELDS", result.get("outcome").asText());
        assertEquals("pickupLabel", result.get("warnings").get(0).get("field").asText());
        assertEquals(before, employee(ACME, id));
        assertEquals(1L, employee(ACME, id).get("hr_version"));
        assertEquals(1, outboxCount(ACME, id));
    }

    @Test
    void unownedFieldsForAnUnknownEmployeeLeaveNoRowBehind() throws Exception {
        String id = unique("UNOWNED");

        assertEquals("IGNORED_NO_OWNED_FIELDS", outcome(send(operator(), hr(id, "UPDATE", 5, Map.of("pickupLabel", "Only Unowned"))), 0));

        assertEquals(0, employeeCount(ACME, id));
        assertEquals(0, outboxCount(ACME, id));
    }

    @Test
    void aRejectedRecordResentWithTheSameEventIdAndCorrectedDataIsApplied() throws Exception {
        String id = unique("FIXED");
        String eventId = unique("evt");
        String token = operator();

        MockHttpServletResponse bad = send(token, event(eventId, id, "HR", "UPDATE", 2, Map.of("phone", "12345")));
        MockHttpServletResponse corrected = send(token, event(eventId, id, "HR", "UPDATE", 2, Map.of("phone", "+919000005001")));

        assertEquals("REJECTED", outcome(bad, 0));
        assertEquals("APPLIED", outcome(corrected, 0));
        assertEquals("+919000005001", employee(ACME, id).get("phone"));
    }

    @Test
    void aRecordRejectedForAnUnknownOfficeCanAlsoBeResent() throws Exception {
        String id = unique("FIXED");
        String eventId = unique("evt");
        String token = operator();

        assertEquals("REJECTED", outcome(send(token, event(eventId, id, "HR", "UPDATE", 2, Map.of("officeCode", "NOPE"))), 0));
        assertEquals("APPLIED", outcome(send(token, event(eventId, id, "HR", "UPDATE", 2, Map.of("officeCode", "BLR-WHF"))), 0));
    }

    @Test
    void aBatchOverTheMaximumIs400AndAppliesNothing() throws Exception {
        String prefix = unique("HUGE");
        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            batch.add(newJoiner(prefix + "-" + i));
        }

        MockHttpServletResponse response = sendList(operator(), batch);

        assertEquals(400, response.getStatus());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE external_id LIKE ?", Integer.class, prefix + "%"));
    }

    @Test
    void aBatchAtTheMaximumIsAccepted() throws Exception {
        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            batch.add(Map.of("eventId", "not-valid"));
        }

        MockHttpServletResponse response = sendList(operator(), batch);

        assertEquals(207, response.getStatus());
        assertEquals(1000, body(response).get("rejected").asInt());
    }

    @Test
    void anUnparseableEnvelopeIs400() throws Exception {
        for (String envelope : List.of("{not json", "{\"events\":\"nope\"}", "{}", "{\"events\":null}")) {
            MockHttpServletResponse response = mockMvc.perform(post("/api/v1/roster/events")
                            .header("Authorization", "Bearer " + operator())
                            .contentType("application/json").content(envelope))
                    .andReturn().getResponse();
            assertEquals(400, response.getStatus(), envelope);
        }
    }

    @Test
    void aRecordWithWrongTypesFailsAloneNotTheWholeBatch() throws Exception {
        String good = unique("GOOD");

        MockHttpServletResponse response = send(operator(),
                Map.of("eventId", 5, "employeeExternalId", List.of(1), "version", "seven", "source", true),
                1,
                newJoiner(good));

        assertEquals(207, response.getStatus());
        assertEquals("REJECTED", outcome(response, 0));
        assertEquals("REJECTED", outcome(response, 1));
        assertEquals("APPLIED", outcome(response, 2));
    }

    @Test
    void anInvalidIdIsNotEchoedBack() throws Exception {
        String longId = "x".repeat(101);

        MockHttpServletResponse response = send(operator(), hr(longId, "UPDATE", 1, Map.of()));

        assertTrue(body(response).get("results").get(0).get("employeeExternalId").isNull());
        assertFalse(response.getContentAsString().contains(longId));
    }

    @Test
    void aValueTooLongForItsColumnNamesTheFieldFailsAloneAndItsKeyStaysFree() throws Exception {
        String id = unique("LONG");
        String good = unique("GOOD");
        String eventId = unique("evt");
        String token = operator();

        MockHttpServletResponse response = send(token,
                event(eventId, id, "HR", "UPDATE", 2, Map.of("name", "n".repeat(300))), newJoiner(good));

        assertEquals(207, response.getStatus());
        JsonNode result = body(response).get("results").get(0);
        assertEquals("REJECTED", result.get("outcome").asText());
        assertEquals("FIELD_TOO_LONG", result.get("errors").get(0).get("code").asText());
        assertEquals("name", result.get("errors").get(0).get("field").asText());
        assertEquals("APPLIED", outcome(response, 1));
        assertEquals(0, employeeCount(ACME, id));
        assertEquals("APPLIED", outcome(send(token, event(eventId, id, "HR", "UPDATE", 2, Map.of("name", "Demo Short"))), 0));
    }

    @Test
    void employeeAndAdminCallingTheEndpointGet403AndApplyNothing() throws Exception {
        String id = unique("ROLE");
        Map<String, Object> event = newJoiner(id);

        for (String user : List.of("acme.employee", "acme.admin")) {
            MockHttpServletResponse response = send(tokenFor("ACME", user), event);
            assertEquals(403, response.getStatus(), user);
            assertEquals("ACCESS_DENIED", body(response).get("code").asText());
        }
        assertEquals(0, employeeCount(ACME, id));
    }

    @Test
    void noTokenIs401() throws Exception {
        assertEquals(401, mockMvc.perform(post("/api/v1/roster/events").contentType("application/json").content("{\"events\":[]}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void acmeOperatorIngestingGlx2001CreatesAnAcmeRowAndLeavesGlobexUntouched() throws Exception {
        String id = "GLX-2001";
        Map<String, Object> globexBefore = employee(GLOBEX, id);
        int globexOutboxBefore = outboxCount(GLOBEX, id);
        try {
            MockHttpServletResponse response = send(operator(), hr(id, "UPDATE", 99, Map.of("name", "Demo Acme Person", "officeCode", "BLR-WHF")));

            assertEquals("APPLIED", outcome(response, 0));
            assertEquals("Demo Acme Person", employee(ACME, id).get("name"));
            assertEquals(99L, employee(ACME, id).get("hr_version"));
            assertEquals(globexBefore, employee(GLOBEX, id));
            assertEquals(globexOutboxBefore, outboxCount(GLOBEX, id));
        } finally {
            jdbc.update("DELETE FROM outbox WHERE tenant_id = 1 AND aggregate_id = ?", id);
            jdbc.update("DELETE FROM roster_event WHERE tenant_id = 1 AND employee_external_id = ?", id);
            jdbc.update("DELETE FROM employee WHERE tenant_id = 1 AND external_id = ?", id);
        }
    }

    @Test
    void theSameEventIdIsIndependentPerTenant() throws Exception {
        String eventId = unique("evt");
        Map<String, Object> event = event(eventId, unique("TENANT"), "HR", "UPDATE", 1, Map.of("name", "Demo"));

        assertEquals("APPLIED", outcome(send(operator(), event), 0));
        assertEquals("APPLIED", outcome(send(tokenFor("GLOBEX", "globex.operator"), event), 0));
    }

    @Test
    void deactivateIsIdempotentAndASecondOneWritesNoSecondOutboxRow() throws Exception {
        String id = unique("LEAVER");
        String token = operator();
        send(token, newJoiner(id));

        Map<String, Object> deactivate = hr(id, "DEACTIVATE", 2, Map.of());
        assertEquals("APPLIED", outcome(send(token, deactivate), 0));
        Map<String, Object> afterFirst = employee(ACME, id);
        assertEquals("INACTIVE", afterFirst.get("status"));
        assertNotNull(afterFirst.get("deactivated_at"));
        assertEquals(2L, afterFirst.get("hr_version"));
        assertEquals(List.of("EMPLOYEE_CREATED", "EMPLOYEE_DEACTIVATED"), outboxTypes(id));

        assertEquals("DUPLICATE_NOOP", outcome(send(token, deactivate), 0), "same event again");
        assertEquals("DUPLICATE_NOOP", outcome(send(token, hr(id, "DEACTIVATE", 3, Map.of())), 0), "new event, newer version");

        assertEquals(afterFirst, employee(ACME, id));
        assertEquals(List.of("EMPLOYEE_CREATED", "EMPLOYEE_DEACTIVATED"), outboxTypes(id));
    }

    @Test
    void theDeactivationServiceItselfIsANoOpOnAnInactiveEmployee() throws Exception {
        String id = unique("LEAVER");
        send(operator(), newJoiner(id));
        long employeeId = (Long) employee(ACME, id).get("id");
        TenantContext.set(ACME);
        try {
            assertEquals(Boolean.TRUE, transactionTemplate.execute(s -> deactivations.deactivate(employeeId, DeactivationReason.MISSING_FROM_EXPORT, DeactivationTrigger.RECONCILIATION)));
            assertEquals(Boolean.FALSE, transactionTemplate.execute(s -> deactivations.deactivate(employeeId, DeactivationReason.MISSING_FROM_EXPORT, DeactivationTrigger.RECONCILIATION)));
        } finally {
            TenantContext.clear();
        }
        assertEquals(List.of("EMPLOYEE_CREATED", "EMPLOYEE_DEACTIVATED"), outboxTypes(id));
    }

    @Test
    void aTransportEventCannotDeactivateAnEmployee() throws Exception {
        String id = unique("LEAVER");
        String token = operator();
        send(token, newJoiner(id));

        JsonNode result = body(send(token, event(unique("evt"), id, "TRANSPORT", "DEACTIVATE", 5, Map.of()))).get("results").get(0);

        assertEquals("IGNORED_NO_OWNED_FIELDS", result.get("outcome").asText());
        assertEquals("status", result.get("warnings").get(0).get("field").asText());
        assertEquals("ACTIVE", employee(ACME, id).get("status"));
    }

    @Test
    void aDeactivationForAnUnknownEmployeeKeepsAnInactiveRowSoALateCreateIsStale() throws Exception {
        String id = unique("TOMBSTONE");
        String token = operator();

        assertEquals("APPLIED", outcome(send(token, hr(id, "DEACTIVATE", 5, Map.of())), 0));
        assertEquals("INACTIVE", employee(ACME, id).get("status"));

        assertEquals("IGNORED_STALE", outcome(send(token, newJoiner(id)), 0));
        assertEquals("INACTIVE", employee(ACME, id).get("status"));
    }

    @Test
    void aNewerCreateForSomeoneWhoLeftIsARehire() throws Exception {
        String id = unique("REHIRE");
        String token = operator();
        send(token, newJoiner(id));
        send(token, hr(id, "DEACTIVATE", 2, Map.of()));

        Map<String, Object> rehire = hr(id, "CREATE", 3, Map.of("name", "Demo Back", "email", "back@acme.example", "officeCode", "BLR-ECO"));
        assertEquals("APPLIED", outcome(send(token, rehire), 0));

        Map<String, Object> row = employee(ACME, id);
        assertEquals("ACTIVE", row.get("status"));
        assertNull(row.get("deactivated_at"));
        assertEquals("Demo Back", row.get("name"));
    }

    @Test
    void anUpdateForSomeoneWhoLeftAppliesFieldsButDoesNotReactivate() throws Exception {
        String id = unique("LEFT");
        String token = operator();
        send(token, newJoiner(id));
        send(token, hr(id, "DEACTIVATE", 2, Map.of()));

        assertEquals("APPLIED", outcome(send(token, hr(id, "UPDATE", 3, Map.of("shiftCode", "S2"))), 0));

        assertEquals("INACTIVE", employee(ACME, id).get("status"));
        assertEquals("S2", employee(ACME, id).get("shift_code"));
    }

    @Test
    void anUpdateForAnUnknownEmployeeUpsertsAndLogsTheLostCreate(CapturedOutput output) throws Exception {
        String id = unique("UPSERT");

        assertEquals("APPLIED", outcome(send(operator(), hr(id, "UPDATE", 4, Map.of("name", "Demo"))), 0));

        assertEquals(List.of("EMPLOYEE_CREATED"), outboxTypes(id));
        assertTrue(output.getAll().contains("No CREATE was seen for employee " + id));
    }

    @Test
    void aTransportEventForAnUnknownEmployeeCreatesAPartialRow() throws Exception {
        String id = unique("PARTIAL");

        assertEquals("APPLIED", outcome(send(operator(), event(unique("evt"), id, "TRANSPORT", "UPDATE", 2, Map.of("pickupLabel", "Demo Gate"))), 0));

        Map<String, Object> row = employee(ACME, id);
        assertNull(row.get("name"));
        assertEquals(0L, row.get("hr_version"));
        assertEquals(2L, row.get("transport_version"));
        assertNull(row.get("hr_updated_at"));
    }

    @Test
    void hashAndBucketFollowTheCoreRules() throws Exception {
        String id = unique("HASH");
        String token = operator();
        send(token, newJoiner(id));

        Map<String, Object> row = employee(ACME, id);
        assertEquals(BucketAssigner.bucket(id, 256), ((Number) row.get("bucket")).intValue());
        assertEquals(ContentHasher.hash("Demo Joiner", "joiner@acme.example", "+919000005000", null, "BLR-WHF", "S1"), row.get("content_hash"));

        send(token, hr(id, "UPDATE", 2, Map.of("shiftCode", "S2")));

        assertEquals(ContentHasher.hash("Demo Joiner", "joiner@acme.example", "+919000005000", null, "BLR-WHF", "S2"), employee(ACME, id).get("content_hash"));
    }

    @Test
    void anExplicitNullClearsAFieldAndAnAbsentKeyLeavesIt() throws Exception {
        String id = unique("NULL");
        String token = operator();
        send(token, newJoiner(id));
        java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
        payload.put("shiftCode", null);

        send(token, hr(id, "UPDATE", 2, payload));

        Map<String, Object> row = employee(ACME, id);
        assertNull(row.get("shift_code"));
        assertEquals("Demo Joiner", row.get("name"));
    }

    @Test
    void theOutboxPayloadCarriesIdsOnlyAndItsOwnRowId() throws Exception {
        String id = unique("PAYLOAD");
        send(operator(), hr(id, "UPDATE", 8, Map.of("name", "Demo Secret Name", "email", "secret@acme.example", "phone", "+919000005002")));

        Map<String, Object> row = jdbc.queryForMap("SELECT id, tenant_id, event_type, status, CAST(payload AS CHAR) AS payload FROM outbox WHERE aggregate_id = ?", id);
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));

        assertEquals(Set.of("eventId", "eventType", "tenantId", "occurredAt", "employeeExternalId", "changedFields", "hrVersion"), fieldNames(payload));
        assertEquals(row.get("id"), payload.get("eventId").asLong());
        assertEquals("EMPLOYEE_CREATED", payload.get("eventType").asText());
        assertEquals(ACME, payload.get("tenantId").asLong());
        assertEquals("2026-10-30T02:14:11Z", payload.get("occurredAt").asText());
        assertEquals(id, payload.get("employeeExternalId").asText());
        assertEquals(8, payload.get("hrVersion").asInt());
        assertEquals("PENDING", row.get("status"));
        Set<String> changed = new TreeSet<>();
        payload.get("changedFields").forEach(field -> changed.add(field.asText()));
        assertEquals(Set.of("name", "email", "phone"), changed);
        for (String personal : List.of("Demo Secret Name", "secret@acme.example", "+919000005002")) {
            assertFalse(row.get("payload").toString().contains(personal));
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void personalDataIsNotLoggedEvenAtDebug(CapturedOutput output) throws Exception {
        String marker = "Pii-Marker-" + unique("x");
        send(operator(),
                hr(unique("LOG"), "UPDATE", 2, Map.of("name", marker, "email", "pii.marker@acme.example", "phone", "+919000005003")),
                hr(unique("LOG"), "UPDATE", 2, Map.of("phone", "pii.bad.phone")),
                hr(unique("LOG"), "UPDATE", 2, Map.of("name", marker + "n".repeat(300))));

        String log = output.getAll();
        assertTrue(log.contains("IngestRequest[events=3]"), "DEBUG lines must really be on for this test to mean anything");
        for (String personal : List.of(marker, "pii.marker@acme.example", "+919000005003", "pii.bad.phone")) {
            assertFalse(log.contains(personal), "the log contained " + personal);
        }
    }

    @Test
    void twoRequestsWithTheSameEventIdApplyExactlyOnce() throws Exception {
        String id = unique("RACE");
        Map<String, Object> event = newJoiner(id);
        String token = operator();

        List<String> outcomes = race(8, () -> outcome(send(token, event), 0));

        assertEquals(1, outcomes.stream().filter("APPLIED"::equals).count(), outcomes.toString());
        assertEquals(7, outcomes.stream().filter("DUPLICATE_NOOP"::equals).count(), outcomes.toString());
        assertEquals(1, outboxCount(ACME, id));
    }

    @Test
    void manyThreadsIngestingOneNewEmployeeEndAtTheHighestVersion() throws Exception {
        String id = unique("RACE");
        String token = operator();
        List<Callable<String>> calls = new ArrayList<>();
        for (int version = 1; version <= 8; version++) {
            Map<String, Object> event = hr(id, "UPDATE", version, Map.of("shiftCode", "S" + version));
            calls.add(() -> outcome(send(token, event), 0));
        }

        List<String> outcomes = raceAll(calls);

        assertTrue(outcomes.stream().noneMatch("REJECTED"::equals), outcomes.toString());
        assertEquals(1, employeeCount(ACME, id));
        assertEquals(8L, employee(ACME, id).get("hr_version"));
        assertEquals("S8", employee(ACME, id).get("shift_code"));
        assertEquals(outcomes.stream().filter("APPLIED"::equals).count(), (long) outboxCount(ACME, id));
    }

    private static List<String> race(int threads, Callable<String> call) throws Exception {
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(call);
        }
        return raceAll(calls);
    }

    private static List<String> raceAll(List<Callable<String>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
