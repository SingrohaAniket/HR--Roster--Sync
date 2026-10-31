package com.moveinsync.roster.ingest;

import com.moveinsync.roster.domain.EventOutcome;
import java.util.List;
import java.util.UUID;

public record IngestResponse(
        String batchId,
        int received,
        long applied,
        long ignoredStale,
        long duplicates,
        long ignoredNoOwnedFields,
        long rejected,
        long notProcessed,
        List<EventResult> results) {

    static IngestResponse of(List<EventResult> results) {
        return new IngestResponse(
                UUID.randomUUID().toString(),
                results.size(),
                count(results, EventOutcome.APPLIED),
                count(results, EventOutcome.IGNORED_STALE),
                count(results, EventOutcome.DUPLICATE_NOOP),
                count(results, EventOutcome.IGNORED_NO_OWNED_FIELDS),
                count(results, EventOutcome.REJECTED),
                count(results, EventOutcome.NOT_PROCESSED),
                results);
    }

    private static long count(List<EventResult> results, EventOutcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }
}
