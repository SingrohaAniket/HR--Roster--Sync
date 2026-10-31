package com.moveinsync.roster.core.brake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SafetyBrakeTest {

    private static final BigDecimal THIRTY = new BigDecimal("30");

    @Test
    void justUnderTheThresholdPasses() {
        BrakeDecision decision = SafetyBrake.evaluate(299, 1000, THIRTY);

        assertFalse(decision.tripped());
        assertEquals(new BigDecimal("29.90"), decision.missingPercent());
    }

    @Test
    void exactlyAtTheThresholdTrips() {
        BrakeDecision decision = SafetyBrake.evaluate(300, 1000, THIRTY);

        assertTrue(decision.tripped());
        assertEquals(new BigDecimal("30.00"), decision.missingPercent());
    }

    @Test
    void justOverTheThresholdTrips() {
        BrakeDecision decision = SafetyBrake.evaluate(301, 1000, THIRTY);

        assertTrue(decision.tripped());
        assertEquals(new BigDecimal("30.10"), decision.missingPercent());
    }

    @Test
    void aFractionUnderTheThresholdPassesAndIsNotReportedAsAtIt() {
        BrakeDecision decision = SafetyBrake.evaluate(29_999, 100_000, THIRTY);

        assertFalse(decision.tripped());
        assertEquals(new BigDecimal("29.99"), decision.missingPercent());
    }

    @Test
    void useCaseTwoFileCutOffAt120000Rows() {
        BrakeDecision decision = SafetyBrake.evaluate(80_000, 200_000, THIRTY);

        assertTrue(decision.tripped());
        assertEquals(new BigDecimal("40.00"), decision.missingPercent());
    }

    @Test
    void raisingTheThresholdTo50LetsTheSameExportPass() {
        assertTrue(SafetyBrake.evaluate(80_000, 200_000, THIRTY).tripped());
        assertFalse(SafetyBrake.evaluate(80_000, 200_000, new BigDecimal("50")).tripped());
    }

    @Test
    void firstImportWithNothingActivePasses() {
        BrakeDecision decision = SafetyBrake.evaluate(0, 0, THIRTY);

        assertFalse(decision.tripped());
        assertEquals(new BigDecimal("0.00"), decision.missingPercent());
    }

    @Test
    void missingRowsWithNothingActiveFailSafe() {
        BrakeDecision decision = SafetyBrake.evaluate(5, 0, THIRTY);

        assertTrue(decision.tripped());
        assertEquals(new BigDecimal("100.00"), decision.missingPercent());
    }

    @Test
    void nothingMissingNeverTripsEvenAtThresholdZero() {
        assertFalse(SafetyBrake.evaluate(0, 1000, BigDecimal.ZERO).tripped());
    }

    @Test
    void thresholdZeroTripsOnAnyMissingRow() {
        assertTrue(SafetyBrake.evaluate(1, 1000, BigDecimal.ZERO).tripped());
    }

    @Test
    void everythingMissingTripsEvenAtThreshold100() {
        assertTrue(SafetyBrake.evaluate(1000, 1000, new BigDecimal("100")).tripped());
    }

    @Test
    void honoursAFractionalThreshold() {
        assertFalse(SafetyBrake.evaluate(304, 1000, new BigDecimal("30.50")).tripped());
        assertTrue(SafetyBrake.evaluate(305, 1000, new BigDecimal("30.50")).tripped());
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> SafetyBrake.evaluate(-1, 1000, THIRTY));
        assertThrows(IllegalArgumentException.class, () -> SafetyBrake.evaluate(1, -1000, THIRTY));
    }
}
