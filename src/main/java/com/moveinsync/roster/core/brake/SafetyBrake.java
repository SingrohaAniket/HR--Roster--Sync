package com.moveinsync.roster.core.brake;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SafetyBrake {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENT_SCALE = 2;

    private SafetyBrake() {
    }

    public static BrakeDecision evaluate(long missing, long activeBefore, BigDecimal thresholdPercent) {
        if (missing < 0 || activeBefore < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (missing == 0) {
            return new BrakeDecision(false, percent(0));
        }
        if (activeBefore == 0) {
            return new BrakeDecision(true, percent(100));
        }
        BigDecimal missingTimesHundred = BigDecimal.valueOf(missing).multiply(HUNDRED);
        boolean tripped = missingTimesHundred.compareTo(thresholdPercent.multiply(BigDecimal.valueOf(activeBefore))) >= 0;
        return new BrakeDecision(tripped,
                missingTimesHundred.divide(BigDecimal.valueOf(activeBefore), PERCENT_SCALE, RoundingMode.DOWN));
    }

    private static BigDecimal percent(long value) {
        return BigDecimal.valueOf(value).setScale(PERCENT_SCALE);
    }
}
