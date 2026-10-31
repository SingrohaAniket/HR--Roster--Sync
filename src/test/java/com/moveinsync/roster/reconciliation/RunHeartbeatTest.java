package com.moveinsync.roster.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RunHeartbeatTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger writes = new AtomicInteger();
    private final RunHeartbeat heartbeat = new RunHeartbeat(writes::incrementAndGet, 30, clock::get);

    @Test
    void doesNotWriteInsideTheInterval() {
        clock.addAndGet(29);

        heartbeat.run();
        heartbeat.run();

        assertEquals(0, writes.get());
    }

    @Test
    void writesOnceAtTheIntervalThenWaitsAgain() {
        clock.addAndGet(30);
        heartbeat.run();
        heartbeat.run();
        assertEquals(1, writes.get());

        clock.addAndGet(29);
        heartbeat.run();
        assertEquals(1, writes.get());

        clock.addAndGet(1);
        heartbeat.run();
        assertEquals(2, writes.get());
    }

    @Test
    void aFailedWriteIsTriedAgainOnTheNextBeat() {
        RunHeartbeat failingOnce = new RunHeartbeat(new Runnable() {
            private boolean failed;

            @Override
            public void run() {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("database blip");
                }
                writes.incrementAndGet();
            }
        }, 30, clock::get);
        clock.addAndGet(30);

        try {
            failingOnce.run();
        } catch (IllegalStateException expected) {
            assertEquals(0, writes.get());
        }
        failingOnce.run();

        assertEquals(1, writes.get());
    }
}
