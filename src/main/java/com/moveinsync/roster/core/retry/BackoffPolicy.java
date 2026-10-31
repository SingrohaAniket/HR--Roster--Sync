package com.moveinsync.roster.core.retry;

import java.time.Duration;

public final class BackoffPolicy {

    private final Duration base;
    private final Duration cap;

    public BackoffPolicy(Duration base, Duration cap) {
        if (base.isZero() || base.isNegative()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must not be below base");
        }
        this.base = base;
        this.cap = cap;
    }

    public Duration delayFor(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        int doublings = attempts - 1;
        long largestFactorUnderCap = cap.dividedBy(base);
        if (doublings >= Long.SIZE - 1 || (1L << doublings) > largestFactorUnderCap) {
            return cap;
        }
        return base.multipliedBy(1L << doublings);
    }
}
