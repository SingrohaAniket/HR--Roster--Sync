package com.moveinsync.roster.outbox;

import com.moveinsync.roster.config.OutboxProperties;
import com.moveinsync.roster.core.retry.BackoffPolicy;
import com.moveinsync.roster.observability.RosterMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    private static final Duration BACKOFF_CAP = Duration.ofMinutes(5);
    static final BackoffPolicy BACKOFF = new BackoffPolicy(BACKOFF_BASE, BACKOFF_CAP);
    private static final int MAX_SETTLE_ATTEMPTS = 3;

    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;
    private final RosterMetrics metrics;

    @Autowired
    public OutboxRelay(OutboxStore store, OutboxPublisher publisher, OutboxProperties properties, RosterMetrics metrics) {
        if (Duration.ofSeconds(properties.leaseSeconds()).compareTo(Duration.ofMillis(properties.confirmTimeoutMs())) <= 0) {
            throw new IllegalArgumentException("roster.outbox.lease-seconds must outlast roster.outbox.confirm-timeout-ms");
        }
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    public int relayOnce() {
        List<ClaimedMessage> claimed;
        try {
            claimed = store.claim(properties.batchSize(), properties.leaseSeconds());
        } catch (PessimisticLockingFailureException e) {
            log.debug("Outbox claim lost a lock race; retrying on the next poll");
            return 0;
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        log.info("Outbox relay claimed {} row(s), first id {}", claimed.size(), claimed.get(0).id());

        Set<Long> acknowledged = publisher.publish(claimed);

        settle(claimed, acknowledged);
        return claimed.size();
    }

    private void settle(List<ClaimedMessage> claimed, Set<Long> acknowledged) {
        Instant now = Instant.now();
        List<Long> published = new ArrayList<>();
        List<OutboxStore.Retry> retries = new ArrayList<>();
        List<ClaimedMessage> parked = new ArrayList<>();
        for (ClaimedMessage message : claimed) {
            int attempts = message.attempts() + 1;
            if (acknowledged.contains(message.id())) {
                published.add(message.id());
            } else if (attempts >= properties.maxAttempts()) {
                parked.add(message);
            } else {
                retries.add(new OutboxStore.Retry(message.id(), now.plus(BACKOFF.delayFor(attempts))));
            }
        }
        settleWithRetry(published, retries, parked.stream().map(ClaimedMessage::id).toList());

        metrics.outboxPublished(published.size());
        metrics.outboxRetried(retries.size());
        metrics.outboxFailed(parked.size());
        log.info("Outbox relay settled: {} published, {} to retry, {} parked", published.size(), retries.size(), parked.size());
        parked.forEach(message -> log.error("OUTBOX_ROW_PARKED outboxId={} eventType={} attempts={} status=FAILED",
                message.id(), message.eventType(), message.attempts() + 1));
    }

    private void settleWithRetry(List<Long> published, List<OutboxStore.Retry> retries, List<Long> failed) {
        for (int attempt = 1; ; attempt++) {
            try {
                store.settle(published, retries, failed);
                return;
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_SETTLE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}
