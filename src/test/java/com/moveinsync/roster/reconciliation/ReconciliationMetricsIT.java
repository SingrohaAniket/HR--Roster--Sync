package com.moveinsync.roster.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ReconciliationMetricsIT extends AbstractReconciliationIT {

    @Autowired
    private MeterRegistry registry;

    private double trips() {
        return registry.get("roster.reconciliation.safety_brake.trips").counter().count();
    }

    private double deactivationsBy(String trigger) {
        return registry.get("roster.deactivations").tag("trigger", trigger).counter().count();
    }

    @Test
    void aSafetyBrakeTripIncrementsItsCounterExactlyOnce() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("TRIP", 10, "S1"), "ACTIVE", BEFORE_EXPORT);
        double tripsBefore = trips();

        JsonNode report = run(tenant, csv(people("TRIP", 5, "S1")));

        assertEquals("AWAITING_APPROVAL", report.get("status").asText());
        assertEquals(tripsBefore + 1, trips());

        resolve(tenant.token(), report.get("id").asLong(), "approve");

        assertEquals(tripsBefore + 1, trips(), "approving is not a second trip");
    }

    @Test
    void aRunThatPassesTheBrakeDoesNotCountATrip() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("PASS", 10, "S1"), "ACTIVE", BEFORE_EXPORT);
        double tripsBefore = trips();

        run(tenant, csv(people("PASS", 9, "S1")));

        assertEquals(tripsBefore, trips());
    }

    @Test
    void theMissingPercentGaugeReadsTheLastFinishedRun() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("GAUGE", 10, "S1"), "ACTIVE", BEFORE_EXPORT);

        run(tenant, csv(people("GAUGE", 6, "S1")));

        assertEquals(40.0, registry.get("roster.reconciliation.missing.percent").gauge().value());
    }

    @Test
    void theDurationTimerRecordsARun() throws Exception {
        TestTenant tenant = newTenant();
        long before = registry.get("roster.reconciliation.duration").timer().count();

        run(tenant, csv(people("TIMED", 3, "S1")));

        assertEquals(before + 1, registry.get("roster.reconciliation.duration").timer().count());
    }

    @Test
    void deactivationsAreCountedByTrigger() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("DEACT", 10, "S1"), "ACTIVE", BEFORE_EXPORT);
        double reconciliationBefore = deactivationsBy("RECONCILIATION");
        double ingestBefore = deactivationsBy("INGEST_EVENT");

        run(tenant, csv(people("DEACT", 9, "S1")));
        send(tenant.token(), hr("DEACT-0", "DEACTIVATE", SEEDED_HR_VERSION + 1, java.util.Map.of()));

        assertEquals(reconciliationBefore + 1, deactivationsBy("RECONCILIATION"));
        assertEquals(ingestBefore + 1, deactivationsBy("INGEST_EVENT"));
    }

    @Test
    void aRunsLogLinesCarryItsRunIdAndItIsClearedAfterwards(CapturedOutput output) throws Exception {
        TestTenant tenant = newTenant();

        JsonNode report = run(tenant, csv(people("MDC", 3, "S1")));

        long runId = report.get("id").asLong();
        assertTrue(output.getAll().contains("[" + tenant.id() + "] [" + runId + "] "), "the run's lines carry the tenant and the run id");
        assertNull(MDC.get("runId"));
    }
}
