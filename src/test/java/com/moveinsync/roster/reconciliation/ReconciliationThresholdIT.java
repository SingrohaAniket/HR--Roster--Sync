package com.moveinsync.roster.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ReconciliationThresholdIT extends AbstractReconciliationIT {

    @DynamicPropertySource
    static void raiseTheThreshold(DynamicPropertyRegistry registry) {
        registry.add("roster.reconciliation.missing-threshold-percent", () -> "50");
    }

    @Test
    void theSameCutOffExportPassesOnceTheThresholdIsRaisedTo50() throws Exception {
        TestTenant tenant = newTenant();
        String sameFileAsTheBrakeTest = seedCutOffFile(tenant);

        JsonNode report = run(tenant, sameFileAsTheBrakeTest);

        assertEquals("COMPLETED", report.get("status").asText());
        assertEquals(50.0, report.get("thresholdPercent").asDouble());
        assertEquals(40.0, report.get("missingPercent").asDouble());
        assertEquals(800, report.get("missingCount").asInt());
        assertEquals(800, report.get("deactivatedCount").asInt());
        assertEquals(1200, count(tenant, "ACTIVE"));
        assertEquals(800, count(tenant, "INACTIVE"));
        assertEquals(0, parked(report.get("id").asLong()));
    }
}
