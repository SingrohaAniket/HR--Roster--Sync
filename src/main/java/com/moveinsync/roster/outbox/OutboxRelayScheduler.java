package com.moveinsync.roster.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "roster.outbox", name = "scheduling-enabled", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${roster.outbox.poll-interval-ms}")
    public void poll() {
        try {
            relay.relayOnce();
        } catch (RuntimeException e) {
            log.error("Outbox relay run failed: {}", e.getClass().getName());
        }
    }
}
