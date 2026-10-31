package com.moveinsync.roster.outbox;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxStore {

    private final OutboxRepository outbox;

    public OutboxStore(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedMessage> claim(int batchSize, int leaseSeconds) {
        List<ClaimedMessage> claimed = outbox.claimBatch(batchSize).stream()
                .map(row -> new ClaimedMessage(row.getId(), row.getEventType(), row.getPayload(), row.getAttempts()))
                .toList();
        if (!claimed.isEmpty()) {
            outbox.extendLease(claimed.stream().map(ClaimedMessage::id).toList(), leaseSeconds);
        }
        return claimed;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void settle(Collection<Long> published, Collection<Retry> retries, Collection<Long> failed) {
        if (!published.isEmpty()) {
            outbox.markPublished(published);
        }
        retries.forEach(retry -> outbox.markRetry(retry.id(), retry.nextAttemptAt()));
        failed.forEach(outbox::markFailed);
    }

    public record Retry(long id, Instant nextAttemptAt) {
    }
}
