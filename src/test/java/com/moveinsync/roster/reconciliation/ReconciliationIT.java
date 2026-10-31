package com.moveinsync.roster.reconciliation;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.core.reconciliation.ExportCsv;
import com.moveinsync.roster.core.reconciliation.ExportRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class ReconciliationIT extends AbstractReconciliationIT {

    @Autowired
    private ReconciliationApplier applier;

    @Autowired
    private ReconciliationRuns runs;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String seedFullFixture(TestTenant tenant) {
        List<Person> same = people("SAME", 700, "S1");
        seed(tenant, same, "ACTIVE", BEFORE_EXPORT);
        seed(tenant, people("UPD", 100, "S1"), "ACTIVE", BEFORE_EXPORT);
        List<Person> back = people("BACK", 25, "S1");
        seed(tenant, back, "INACTIVE", BEFORE_EXPORT);
        seed(tenant, people("LIVEA", 25, "S1"), "ACTIVE", AFTER_EXPORT);
        seed(tenant, people("LIVEB", 25, "S1"), "ACTIVE", AFTER_EXPORT);
        seed(tenant, people("GONE", 50, "S1"), "ACTIVE", BEFORE_EXPORT);
        seed(tenant, people("LEFT", 20, "S1"), "INACTIVE", BEFORE_EXPORT);

        List<Person> export = new ArrayList<>(same);
        export.addAll(people("UPD", 100, "S2"));
        export.addAll(back);
        export.addAll(people("LIVEA", 25, "S9"));
        export.addAll(people("NEW", 150, "S1"));
        return csv(export);
    }

    @Test
    void aThousandRowFixtureProducesTheExactCountForEveryClassOfChange() throws Exception {
        TestTenant tenant = newTenant();
        String export = seedFullFixture(tenant);

        JsonNode report = run(tenant, export);

        assertEquals("COMPLETED", report.get("status").asText());
        assertEquals(1000, report.get("exportRowCount").asInt());
        assertEquals(900, report.get("activeCountBefore").asInt());
        assertEquals(150, report.get("createdCount").asInt());
        assertEquals(100, report.get("updatedCount").asInt());
        assertEquals(25, report.get("reactivatedCount").asInt());
        assertEquals(50, report.get("deactivatedCount").asInt());
        assertEquals(50, report.get("missingCount").asInt());
        assertEquals(5.55, report.get("missingPercent").asDouble());
        assertEquals(50, report.get("skippedCount").asInt(), "25 in the file and 25 absent from it");
        assertEquals(720, report.get("unchangedCount").asInt(), "700 identical and 20 already gone");
        assertEquals(0, report.get("malformedRowCount").asInt());
        assertEquals(0, report.get("unknownOfficeCount").asInt());

        assertEquals("S2", employee(tenant.id(), "UPD-0").get("shift_code"));
        assertEquals(new Person("UPD-0", "S2").hash(), employee(tenant.id(), "UPD-0").get("content_hash"));
        assertEquals("INACTIVE", employee(tenant.id(), "GONE-0").get("status"));
        assertEquals("ACTIVE", employee(tenant.id(), "BACK-0").get("status"));
        assertEquals("ACTIVE", employee(tenant.id(), "NEW-0").get("status"));

        assertEquals(150, outboxOf(tenant, "EMPLOYEE_CREATED"));
        assertEquals(125, outboxOf(tenant, "EMPLOYEE_UPDATED"));
        assertEquals(50, outboxOf(tenant, "EMPLOYEE_DEACTIVATED"));
    }

    @Test
    void runningTheSameExportTwiceChangesNothingTheSecondTime() throws Exception {
        TestTenant tenant = newTenant();
        String export = seedFullFixture(tenant);
        run(tenant, export);
        List<Map<String, Object>> employeesBefore = employeesOf(tenant);
        int outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE tenant_id = ?", Integer.class, tenant.id());

        JsonNode second = run(tenant, export);

        assertEquals(0, second.get("createdCount").asInt());
        assertEquals(0, second.get("updatedCount").asInt());
        assertEquals(0, second.get("reactivatedCount").asInt());
        assertEquals(0, second.get("deactivatedCount").asInt());
        assertEquals(0, second.get("missingCount").asInt());
        assertEquals(50, second.get("skippedCount").asInt(), "the live rows are still newer than the file");
        assertEquals(1045, second.get("unchangedCount").asInt());
        assertEquals(employeesBefore, employeesOf(tenant));
        assertEquals(outboxBefore, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE tenant_id = ?", Integer.class, tenant.id()));
    }

    @Test
    void aLiveChangeMadeAfterTheExportIsNotReverted() throws Exception {
        TestTenant tenant = newTenant();
        Person person = new Person("SNAP-1", "S1");
        seed(tenant, List.of(person), "ACTIVE", BEFORE_EXPORT);
        assertEquals("APPLIED", outcome(send(tenant.token(), hr("SNAP-1", "UPDATE", 8, Map.of("name", "Changed Live", "shiftCode", "S7"))), 0));
        Map<String, Object> afterLive = employee(tenant.id(), "SNAP-1");

        JsonNode report = run(tenant, csv(List.of(person)));

        assertEquals(1, report.get("skippedCount").asInt());
        assertEquals(0, report.get("updatedCount").asInt());
        assertEquals(afterLive, employee(tenant.id(), "SNAP-1"));
        assertEquals("Changed Live", employee(tenant.id(), "SNAP-1").get("name"));
    }

    @Test
    void someoneWhoJoinedAfterTheExportIsNotTreatedAsMissing() throws Exception {
        TestTenant tenant = newTenant();
        Person inFile = new Person("OLD-1", "S1");
        seed(tenant, List.of(inFile), "ACTIVE", BEFORE_EXPORT);
        send(tenant.token(), event(unique("evt"), "JOINER-1", "HR", "CREATE", 1,
                Map.of("name", "Demo Joiner", "email", "joiner@test.example", "officeCode", OFFICE)));

        JsonNode report = run(tenant, csv(List.of(inFile)));

        assertEquals(0, report.get("missingCount").asInt());
        assertEquals(1, report.get("skippedCount").asInt());
        assertEquals("ACTIVE", employee(tenant.id(), "JOINER-1").get("status"));
    }

    @Test
    void aLiveEventRacingTheApplyPhaseStillWins() {
        TestTenant tenant = uncheckedTenant();
        Person person = new Person("RACE-1", "S1");
        seed(tenant, List.of(person), "ACTIVE", AFTER_EXPORT);

        ReconciliationApplier.ChunkResult result = applier.applyChunk(tenant.id(), GENERATED_AT,
                List.of(new ExportRow("RACE-1", "Stale Name", "stale@test.example", null, null, OFFICE, "S9")),
                Map.of(OFFICE, tenant.officeId()), 256);

        assertEquals(new ReconciliationApplier.ChunkResult(0, 0, 0, 1), result);
        assertEquals("Name RACE-1", employee(tenant.id(), "RACE-1").get("name"));
    }

    private TestTenant uncheckedTenant() {
        try {
            return newTenant();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reconciliationNeverAdvancesHrVersion() throws Exception {
        TestTenant tenant = newTenant();
        String export = seedFullFixture(tenant);

        run(tenant, export);

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE tenant_id = ? AND external_id NOT LIKE 'NEW-%' AND hr_version <> ?",
                Integer.class, tenant.id(), SEEDED_HR_VERSION), "every existing row keeps its version");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE tenant_id = ? AND external_id LIKE 'NEW-%' AND hr_version <> 0",
                Integer.class, tenant.id()), "a row reconciliation created has no version from HR yet");
    }

    @Test
    void aLiveEventAfterARepairIsStillAppliedBecauseTheVersionDidNotMove() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, List.of(new Person("REPAIR-1", "S1")), "ACTIVE", BEFORE_EXPORT);
        assertEquals(1, run(tenant, csv(List.of(new Person("REPAIR-1", "S2")))).get("updatedCount").asInt());

        MockHttpServletResponse live = send(tenant.token(), hr("REPAIR-1", "UPDATE", SEEDED_HR_VERSION + 1, Map.of("shiftCode", "S3")));

        assertEquals("APPLIED", outcome(live, 0));
        assertEquals("S3", employee(tenant.id(), "REPAIR-1").get("shift_code"));
    }

    @Test
    void reconciliationLeavesTransportOwnedFieldsAlone() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, List.of(new Person("PICK-1", "S1")), "ACTIVE", BEFORE_EXPORT);
        jdbc.update("UPDATE employee SET pickup_label = 'Demo Gate', transport_version = 4 WHERE tenant_id = ?", tenant.id());

        run(tenant, csv(List.of(new Person("PICK-1", "S2"))));

        Map<String, Object> row = employee(tenant.id(), "PICK-1");
        assertEquals("S2", row.get("shift_code"));
        assertEquals("Demo Gate", row.get("pickup_label"));
        assertEquals(4L, row.get("transport_version"));
    }

    @Test
    void anInactiveEmployeeInTheExportIsReactivatedNotCreated() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, List.of(new Person("REHIRE-1", "S1")), "INACTIVE", BEFORE_EXPORT);
        Object id = employee(tenant.id(), "REHIRE-1").get("id");

        JsonNode report = run(tenant, csv(List.of(new Person("REHIRE-1", "S1"))));

        assertEquals(1, report.get("reactivatedCount").asInt());
        assertEquals(0, report.get("createdCount").asInt());
        Map<String, Object> row = employee(tenant.id(), "REHIRE-1");
        assertEquals(id, row.get("id"), "the same row");
        assertEquals("ACTIVE", row.get("status"));
        assertNull(row.get("deactivated_at"));
        assertEquals(1, employeeCount(tenant.id(), "REHIRE-1"));
        assertEquals(SEEDED_HR_VERSION, row.get("hr_version"));
    }

    @Test
    void aMalformedRowAndAnUnknownOfficeFailOnlyThemselves() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("KEPT", 2, "S1"), "ACTIVE", BEFORE_EXPORT);
        String export = ExportCsv.HEADER + "\n"
                + "KEPT-0,Secret Bad Name,not-an-email,,," + OFFICE + ",S1\n"
                + "KEPT-1,Secret Other Name,kept1@test.example,,,NO-SUCH-OFFICE,S1\n"
                + "GOOD-1,Name GOOD-1,good1@test.example,,," + OFFICE + ",S1\n"
                + "this,is,not,enough\n"
                + ",no,id,here,at,all,x\n"
                + "GOOD-1,Name Duplicate,dup@test.example,,," + OFFICE + ",S1\n";

        MockHttpServletResponse response = upload(tenant.token(), export, GENERATED_AT);

        assertEquals(201, response.getStatus());
        JsonNode report = body(response);
        assertEquals("COMPLETED", report.get("status").asText());
        assertEquals(6, report.get("exportRowCount").asInt());
        assertEquals(4, report.get("malformedRowCount").asInt(), "a bad email, too few columns, no id, a duplicate id");
        assertEquals(1, report.get("unknownOfficeCount").asInt());
        assertEquals(1, report.get("createdCount").asInt());
        assertEquals(0, report.get("missingCount").asInt(), "named in a bad row means present, not missing");
        assertEquals("ACTIVE", employee(tenant.id(), "KEPT-0").get("status"));
        assertEquals("Name KEPT-0", employee(tenant.id(), "KEPT-0").get("name"), "a bad row changes nothing");
        assertEquals("Name GOOD-1", employee(tenant.id(), "GOOD-1").get("name"), "the first of two duplicates wins");
        for (String value : List.of("Secret Bad Name", "not-an-email", "Secret Other Name", "NO-SUCH-OFFICE")) {
            assertFalse(response.getContentAsString().contains(value), "the report contained " + value);
        }
    }

    @Test
    void aWrongHeaderIs400AndOpensNoRun() throws Exception {
        TestTenant tenant = newTenant();

        MockHttpServletResponse response = upload(tenant.token(), "name,externalId\nx,y\n", GENERATED_AT);

        assertEquals(400, response.getStatus());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_run WHERE tenant_id = ?", Integer.class, tenant.id()));
    }

    @Test
    void aMissingFileOrUnreadableTimeIs400() throws Exception {
        TestTenant tenant = newTenant();

        assertEquals(400, mockMvc.perform(multipart("/api/v1/reconciliation/runs")
                .param("generatedAt", GENERATED_AT.toString()).header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(multipart("/api/v1/reconciliation/runs")
                .file(new MockMultipartFile("file", "x.csv", "text/csv", ExportCsv.HEADER.getBytes()))
                .param("generatedAt", "yesterday").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void personalDataIsNotLoggedEvenAtDebug(CapturedOutput output) throws Exception {
        TestTenant tenant = newTenant();
        String marker = "PiiMarker" + unique("x").replace("-", "");
        run(tenant, ExportCsv.HEADER + "\nLOG-1," + marker + ",logged@test.example,+919000007000,," + OFFICE + ",S1\nLOG-2," + marker + ",not-an-email,,," + OFFICE + ",S1\n");

        String log = output.getAll();
        assertFalse(log.contains(marker), "a name reached the log");
        assertFalse(log.contains("logged@test.example"));
        assertFalse(log.contains("+919000007000"));
    }

    @Test
    void outboxEventsCarryTheirOwnRowIdAcrossAWholeBatchAndNoPersonalData() throws Exception {
        TestTenant tenant = newTenant();

        run(tenant, csv(people("BATCH", 1200, "S1")));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, CAST(payload AS CHAR) AS payload FROM outbox WHERE tenant_id = ?", tenant.id());
        assertEquals(1200, rows.size());
        for (Map<String, Object> row : rows) {
            JsonNode payload = objectMapper.readTree((String) row.get("payload"));
            assertEquals(row.get("id"), payload.get("eventId").asLong(), "the consumer's dedup key must be the row id");
            assertEquals("EMPLOYEE_CREATED", payload.get("eventType").asText());
            assertEquals(GENERATED_AT.toString(), payload.get("occurredAt").asText());
            assertFalse(row.get("payload").toString().contains("@test.example"));
            assertFalse(row.get("payload").toString().contains("Name "));
        }
    }

    @Test
    void aReactivationEventNamesStatusAndAnUpdateNamesOnlyWhatChanged() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, List.of(new Person("EV-1", "S1")), "INACTIVE", BEFORE_EXPORT);
        seed(tenant, List.of(new Person("EV-2", "S1")), "ACTIVE", BEFORE_EXPORT);

        run(tenant, csv(List.of(new Person("EV-1", "S1"), new Person("EV-2", "S2"))));

        assertEquals("[\"status\"]", objectMapper.readTree(jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM outbox WHERE aggregate_id = 'EV-1'", String.class)).get("changedFields").toString());
        assertEquals("[\"shiftCode\"]", objectMapper.readTree(jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM outbox WHERE aggregate_id = 'EV-2'", String.class)).get("changedFields").toString());
    }

    @Test
    void exampleUseCase2AFileCutOffAt60PercentTripsTheBrakeAndDeactivatesNobody(CapturedOutput output) throws Exception {
        TestTenant tenant = newTenant();
        String cutOff = seedCutOffFile(tenant);

        JsonNode report = run(tenant, cutOff);

        assertEquals("AWAITING_APPROVAL", report.get("status").asText());
        assertEquals(1200, report.get("exportRowCount").asInt());
        assertEquals(2000, report.get("activeCountBefore").asInt());
        assertEquals(800, report.get("missingCount").asInt(), "the operator can read how many records are missing");
        assertEquals(40.0, report.get("missingPercent").asDouble());
        assertEquals(30.0, report.get("thresholdPercent").asDouble());
        assertEquals(0, report.get("deactivatedCount").asInt());
        assertEquals(2000, count(tenant, "ACTIVE"), "NOBODY is deactivated");
        assertEquals(0, count(tenant, "INACTIVE"));
        assertEquals(0, outboxOf(tenant, "EMPLOYEE_DEACTIVATED"));
        long runId = report.get("id").asLong();
        assertEquals(800, parked(runId), "candidates stay parked against the run");

        assertTrue(output.getAll().contains("RECONCILIATION_BRAKE_TRIPPED runId=" + runId + " tenantId=" + tenant.id()
                + " activeBefore=2000 missing=800 missingPercent=40.00 thresholdPercent=30 exportRows=1200"));

        JsonNode read = body(mockMvc.perform(get("/api/v1/reconciliation/runs/" + runId).header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse());
        assertEquals("AWAITING_APPROVAL", read.get("status").asText());
        assertEquals(800, read.get("missingCount").asInt());
    }

    @Test
    void whenTheBrakeTripsTheRowsThatDidArriveAreStillApplied() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("KEEP", 10, "S1"), "ACTIVE", BEFORE_EXPORT);
        seed(tenant, List.of(new Person("BACK-1", "S1")), "INACTIVE", BEFORE_EXPORT);

        JsonNode report = run(tenant, csv(List.of(new Person("KEEP-0", "S2"), new Person("BACK-1", "S1"), new Person("NEW-1", "S1"))));

        assertEquals("AWAITING_APPROVAL", report.get("status").asText());
        assertEquals(9, report.get("missingCount").asInt());
        assertEquals(1, report.get("updatedCount").asInt());
        assertEquals(1, report.get("reactivatedCount").asInt());
        assertEquals(1, report.get("createdCount").asInt());
        assertEquals("S2", employee(tenant.id(), "KEEP-0").get("shift_code"));
        assertEquals("ACTIVE", employee(tenant.id(), "KEEP-1").get("status"), "still not deactivated");
    }

    private static final String EMP_1003 = "EMP-1003,Demo Employee Three,emp1003@acme.example,+919000001003,EMP-1005,BLR-ECO,S2";
    private static final String EMP_1004 = "EMP-1004,Demo Employee Four,emp1004@acme.example,+919000001004,EMP-1005,HYD-HTC,S2";
    private static final String EMP_1005 = "EMP-1005,Demo Manager Five,emp1005@acme.example,+919000001005,,BLR-WHF,S1";

    private JsonNode tripTheBrakeOnSeededAcmeData() throws Exception {
        restoreSeed();
        jdbc.update("UPDATE employee SET status = 'INACTIVE' WHERE tenant_id = 1 AND id > 7");
        MockHttpServletResponse response = upload(operator(), ExportCsv.HEADER + "\n" + EMP_1003 + "\n" + EMP_1004 + "\n" + EMP_1005 + "\n", Instant.now());
        assertEquals(201, response.getStatus());
        return body(response);
    }

    private int seats(long tripId) {
        return jdbc.queryForObject("SELECT seats_taken FROM trip WHERE id = ?", Integer.class, tripId);
    }

    private String bookingStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, id);
    }

    @Test
    void approveAppliesTheDeactivationsWithTheirSideEffects() throws Exception {
        try {
            JsonNode report = tripTheBrakeOnSeededAcmeData();
            assertEquals("AWAITING_APPROVAL", report.get("status").asText());
            assertEquals(2, report.get("missingCount").asInt());
            assertEquals(0, report.get("updatedCount").asInt(), "the seeded hashes must match rows built from the same values");
            assertTrue(report.get("unchangedCount").asInt() >= 3);
            assertEquals("ACTIVE", employee(ACME, "EMP-1001").get("status"));
            assertEquals("BOOKED", bookingStatus(3));
            assertEquals(3, seats(2));

            MockHttpServletResponse approved = resolve(operator(), report.get("id").asLong(), "approve");

            assertEquals(200, approved.getStatus());
            JsonNode after = body(approved);
            assertEquals("COMPLETED", after.get("status").asText());
            assertEquals(2, after.get("deactivatedCount").asInt());
            assertEquals("acme.operator", after.get("approvedBy").asText());
            assertNotNull(after.get("approvedAt").asText());
            assertEquals(0, parked(report.get("id").asLong()));

            assertEquals("INACTIVE", employee(ACME, "EMP-1001").get("status"));
            assertEquals("INACTIVE", employee(ACME, "EMP-1002").get("status"));
            assertEquals("ACTIVE", employee(ACME, "EMP-1003").get("status"));
            assertEquals("CANCELLED", bookingStatus(3));
            assertEquals("CANCELLED", bookingStatus(4));
            assertEquals("CANCELLED", bookingStatus(6));
            assertEquals("MISSING_FROM_EXPORT", jdbc.queryForObject("SELECT cancel_reason FROM booking WHERE id = 3", String.class));
            assertEquals("BOOKED", bookingStatus(1), "a past booking is a billing record");
            assertEquals("BOOKED", bookingStatus(2), "a past booking is a billing record");
            assertEquals("BOOKED", bookingStatus(5), "EMP-1005 is still here");
            assertEquals(1, seats(2), "both leavers were on trip 2");
            assertEquals(0, seats(3));
            assertEquals(2, seats(1), "the past trip keeps its seats");
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE tenant_id = 1 AND event_type = 'EMPLOYEE_DEACTIVATED' AND aggregate_id IN ('EMP-1001', 'EMP-1002')", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE tenant_id = 1 AND event_type = 'BOOKING_CANCELLED' AND aggregate_id IN ('3', '4', '6')", Integer.class));

            assertEquals(409, resolve(operator(), report.get("id").asLong(), "approve").getStatus(), "approving twice is refused");
        } finally {
            restoreSeed();
        }
    }

    @Test
    void anEmployeeUpdatedLiveWhileARunAwaitsApprovalIsNotDeactivated() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("WAIT", 10, "S1"), "ACTIVE", BEFORE_EXPORT);
        JsonNode report = run(tenant, csv(people("WAIT", 5, "S1")));
        assertEquals("AWAITING_APPROVAL", report.get("status").asText());

        send(tenant.token(), hr("WAIT-9", "UPDATE", SEEDED_HR_VERSION + 1, Map.of("shiftCode", "S2")));
        JsonNode after = body(resolve(tenant.token(), report.get("id").asLong(), "approve"));

        assertEquals(4, after.get("deactivatedCount").asInt());
        assertEquals(1, after.get("skippedCount").asInt());
        assertEquals("ACTIVE", employee(tenant.id(), "WAIT-9").get("status"));
        assertEquals("INACTIVE", employee(tenant.id(), "WAIT-8").get("status"));
    }

    @Test
    void rejectDiscardsTheCandidatesAndTheRunBecomesAborted() throws Exception {
        TestTenant tenant = newTenant();
        JsonNode report = run(tenant, seedCutOffFile(tenant));
        long runId = report.get("id").asLong();

        MockHttpServletResponse rejected = resolve(tenant.token(), runId, "reject");

        assertEquals(200, rejected.getStatus());
        JsonNode after = body(rejected);
        assertEquals("ABORTED", after.get("status").asText());
        assertEquals("REJECTED_BY_OPERATOR", after.get("abortReason").asText());
        assertEquals(0, after.get("deactivatedCount").asInt());
        assertTrue(after.get("approvedBy").isNull());
        assertEquals(0, parked(runId));
        assertEquals(2000, count(tenant, "ACTIVE"));
        assertEquals(409, resolve(tenant.token(), runId, "approve").getStatus(), "a rejected run cannot then be approved");
        assertEquals(201, upload(tenant.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus(), "the tenant can upload again");
    }

    private void insertOpenRun(TestTenant tenant, String status, Instant startedAt) {
        insertOpenRun(tenant, status, startedAt, startedAt);
    }

    private void insertOpenRun(TestTenant tenant, String status, Instant startedAt, Instant lastProgressAt) {
        jdbc.update("INSERT INTO reconciliation_run (tenant_id, status, started_at, last_progress_at, export_generated_at, threshold_percent) VALUES (?, ?, ?, ?, ?, 30)",
                tenant.id(), status, LocalDateTime.ofInstant(startedAt, ZoneOffset.UTC), LocalDateTime.ofInstant(lastProgressAt, ZoneOffset.UTC),
                LocalDateTime.ofInstant(GENERATED_AT, ZoneOffset.UTC));
    }

    @Test
    void aSecondUploadWhileAnotherIsRunningIsRejected() throws Exception {
        TestTenant tenant = newTenant();
        insertOpenRun(tenant, "RUNNING", Instant.now());

        MockHttpServletResponse response = upload(tenant.token(), ExportCsv.HEADER + "\n", GENERATED_AT);

        assertEquals(409, response.getStatus());
        assertEquals("CONFLICT", body(response).get("code").asText());
    }

    @Test
    void aSecondUploadDuringARealRunningUploadIsRejected() throws Exception {
        TestTenant tenant = newTenant();
        String large = csv(people("BUSY", 5000, "S1"));
        String small = ExportCsv.HEADER + "\n";

        CompletableFuture<MockHttpServletResponse> first = CompletableFuture.supplyAsync(() -> {
            try {
                return upload(tenant.token(), large, GENERATED_AT);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(5)).until(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_run WHERE tenant_id = ? AND status = 'RUNNING'", Integer.class, tenant.id()) == 1);

        assertEquals(409, upload(tenant.token(), small, GENERATED_AT).getStatus());
        assertEquals(201, first.get().getStatus());
    }

    @Test
    void aRunAwaitingApprovalAlsoBlocksANewUpload() throws Exception {
        TestTenant tenant = newTenant();
        insertOpenRun(tenant, "AWAITING_APPROVAL", Instant.now());

        assertEquals(409, upload(tenant.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus());
    }

    @Test
    void aRunOpenInOneTenantDoesNotBlockAnother() throws Exception {
        TestTenant busy = newTenant();
        TestTenant other = newTenant();
        insertOpenRun(busy, "RUNNING", Instant.now());

        assertEquals(201, upload(other.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus());
    }

    @Test
    void anOldRunWithAFreshHeartbeatIsStillWorkingAndIsNotMarkedAbandoned() throws Exception {
        TestTenant tenant = newTenant();
        insertOpenRun(tenant, "RUNNING", Instant.now().minus(Duration.ofHours(2)), Instant.now());

        assertEquals(409, upload(tenant.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus());

        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE tenant_id = ?", String.class, tenant.id()));
    }

    @Test
    void aRunJustPastTheStaleLimitIsAbandonedAndOneJustInsideItIsNot() throws Exception {
        TestTenant stale = newTenant();
        TestTenant fresh = newTenant();
        insertOpenRun(stale, "RUNNING", Instant.now(), Instant.now().minus(Duration.ofMinutes(6)));
        insertOpenRun(fresh, "RUNNING", Instant.now(), Instant.now().minus(Duration.ofMinutes(4)));

        assertEquals(201, upload(stale.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus());
        assertEquals(409, upload(fresh.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus());
    }

    @Test
    void aRunThatWaitedLongForApprovalIsNotAbandonedTheMomentItIsApproved() throws Exception {
        TestTenant tenant = newTenant();
        long runId = run(tenant, seedCutOffFile(tenant)).get("id").asLong();
        jdbc.update("UPDATE reconciliation_run SET last_progress_at = ? WHERE id = ?",
                LocalDateTime.ofInstant(Instant.now().minus(Duration.ofDays(3)), ZoneOffset.UTC), runId);

        assertTrue(runs.claimForResolution(tenant.id(), runId));

        assertEquals(409, upload(tenant.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus(), "claiming it started the heartbeat");
    }

    @Test
    void theHeartbeatMovesTheColumn() throws Exception {
        TestTenant tenant = newTenant();
        insertOpenRun(tenant, "RUNNING", Instant.now(), Instant.now().minus(Duration.ofMinutes(4)));
        long runId = jdbc.queryForObject("SELECT id FROM reconciliation_run WHERE tenant_id = ?", Long.class, tenant.id());

        runs.heartbeat(tenant.id(), runId);

        assertEquals(1, jdbc.queryForObject(
                "SELECT last_progress_at > TIMESTAMPADD(SECOND, -30, CURRENT_TIMESTAMP(3)) FROM reconciliation_run WHERE id = ?", Integer.class, runId));
    }

    @Test
    void aRunAbandonedByADeadProcessStopsBlockingTheTenant() throws Exception {
        TestTenant tenant = newTenant();
        insertOpenRun(tenant, "RUNNING", Instant.now().minus(Duration.ofHours(2)));

        assertEquals(201, upload(tenant.token(), ExportCsv.HEADER + "\n", GENERATED_AT).getStatus());

        assertEquals("ABANDONED", jdbc.queryForObject(
                "SELECT abort_reason FROM reconciliation_run WHERE tenant_id = ? AND status = 'FAILED'", String.class, tenant.id()));
    }

    @Test
    void aGlobexOperatorCannotSeeOrApproveAnAcmeRun() throws Exception {
        try {
            JsonNode report = tripTheBrakeOnSeededAcmeData();
            long runId = report.get("id").asLong();
            String globex = tokenFor("GLOBEX", "globex.operator");

            assertEquals(404, mockMvc.perform(get("/api/v1/reconciliation/runs/" + runId).header("Authorization", "Bearer " + globex))
                    .andReturn().getResponse().getStatus());
            assertEquals(404, resolve(globex, runId, "approve").getStatus());
            assertEquals(404, resolve(globex, runId, "reject").getStatus());

            assertEquals(2, parked(runId), "nothing was touched");
            assertEquals("ACTIVE", employee(ACME, "EMP-1001").get("status"));
            assertEquals(200, resolve(operator(), runId, "reject").getStatus());
        } finally {
            restoreSeed();
        }
    }

    @Test
    void onlyAnOperatorCanUseTheseEndpoints() throws Exception {
        for (String user : List.of("acme.employee", "acme.admin")) {
            String token = tokenFor("ACME", user);
            assertEquals(403, upload(token, ExportCsv.HEADER + "\n", GENERATED_AT).getStatus(), user);
            assertEquals(403, mockMvc.perform(get("/api/v1/reconciliation/runs/1").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus(), user);
            assertEquals(403, resolve(token, 1, "approve").getStatus(), user);
            assertEquals(403, resolve(token, 1, "reject").getStatus(), user);
        }
        assertEquals(401, mockMvc.perform(multipart("/api/v1/reconciliation/runs")).andReturn().getResponse().getStatus());
    }

    @Test
    void anUnknownRunIs404() throws Exception {
        assertEquals(404, mockMvc.perform(get("/api/v1/reconciliation/runs/999999").header("Authorization", "Bearer " + operator())).andReturn().getResponse().getStatus());
    }

    @Test
    void approvingARunThatNeverNeededItIsAConflict() throws Exception {
        TestTenant tenant = newTenant();
        JsonNode report = run(tenant, ExportCsv.HEADER + "\n");

        assertEquals("COMPLETED", report.get("status").asText());
        assertEquals(409, resolve(tenant.token(), report.get("id").asLong(), "approve").getStatus());
    }

    @Test
    void noSpillFilesAreLeftBehind() throws Exception {
        TestTenant tenant = newTenant();
        List<Path> before = workspaces();

        run(tenant, csv(people("CLEAN", 100, "S1")));

        assertEquals(before, workspaces());
    }

    private static List<Path> workspaces() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("roster-reconciliation-")).toList();
        }
    }
}
