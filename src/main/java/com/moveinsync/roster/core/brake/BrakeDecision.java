package com.moveinsync.roster.core.brake;

import java.math.BigDecimal;

public record BrakeDecision(boolean tripped, BigDecimal missingPercent) {
}
