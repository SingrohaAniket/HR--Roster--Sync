package com.moveinsync.roster.reconciliation;

import java.util.function.LongSupplier;

final class RunHeartbeat implements Runnable {

    private final Runnable write;
    private final long intervalNanos;
    private final LongSupplier clockNanos;
    private long lastWrite;

    RunHeartbeat(Runnable write, long intervalNanos, LongSupplier clockNanos) {
        this.write = write;
        this.intervalNanos = intervalNanos;
        this.clockNanos = clockNanos;
        this.lastWrite = clockNanos.getAsLong();
    }

    @Override
    public void run() {
        long now = clockNanos.getAsLong();
        if (now - lastWrite >= intervalNanos) {
            write.run();
            lastWrite = now;
        }
    }
}
