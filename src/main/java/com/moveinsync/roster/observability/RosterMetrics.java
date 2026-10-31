package com.moveinsync.roster.observability;

import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.deactivation.DeactivationTrigger;
import com.moveinsync.roster.domain.EventOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RosterMetrics {

    private final Counter outboxPublished;
    private final Counter outboxFailed;
    private final Counter outboxRetries;
    private final Counter brakeTrips;
    private final Timer ingestBatch;
    private final Timer reconciliationDuration;
    private final Map<EventOutcome, Counter> ingestEvents = new EnumMap<>(EventOutcome.class);
    private final Map<ErrorCode, Counter> ingestRejections = new EnumMap<>(ErrorCode.class);
    private final Map<DeactivationTrigger, Counter> deactivations = new EnumMap<>(DeactivationTrigger.class);

    public RosterMetrics(MeterRegistry registry) {
        outboxPublished = Counter.builder("roster.outbox.published")
                .description("Outbox rows the broker acknowledged").register(registry);
        outboxFailed = Counter.builder("roster.outbox.failed")
                .description("Outbox rows parked for a human after their last attempt").register(registry);
        outboxRetries = Counter.builder("roster.outbox.retries")
                .description("Outbox publishes that were not acknowledged and will be tried again").register(registry);
        brakeTrips = Counter.builder("roster.reconciliation.safety_brake.trips")
                .description("Exports the safety brake stopped. Page someone").register(registry);
        ingestBatch = Timer.builder("roster.ingest.batch")
                .description("Time to process one ingest batch").publishPercentiles(0.5, 0.99).register(registry);
        reconciliationDuration = Timer.builder("roster.reconciliation.duration")
                .description("Time for one reconciliation run, against the 60 second budget")
                .publishPercentiles(0.5, 0.99).register(registry);
        for (EventOutcome outcome : EventOutcome.values()) {
            ingestEvents.put(outcome, Counter.builder("roster.ingest.events")
                    .description("Ingested events by outcome").tag("outcome", outcome.name()).register(registry));
        }
        for (ErrorCode code : ErrorCode.values()) {
            ingestRejections.put(code, Counter.builder("roster.ingest.rejections")
                    .description("Rejected events by reason").tag("code", code.name()).register(registry));
        }
        for (DeactivationTrigger trigger : DeactivationTrigger.values()) {
            deactivations.put(trigger, Counter.builder("roster.deactivations")
                    .description("Employees deactivated, by what caused it").tag("trigger", trigger.name()).register(registry));
        }
    }

    public void outboxPublished(int rows) {
        outboxPublished.increment(rows);
    }

    public void outboxFailed(int rows) {
        outboxFailed.increment(rows);
    }

    public void outboxRetried(int rows) {
        outboxRetries.increment(rows);
    }

    public void brakeTripped() {
        brakeTrips.increment();
    }

    public void ingestEvent(EventOutcome outcome) {
        ingestEvents.get(outcome).increment();
    }

    public void ingestRejection(ErrorCode code) {
        ingestRejections.get(code).increment();
    }

    public void deactivated(DeactivationTrigger trigger) {
        deactivations.get(trigger).increment();
    }

    public Timer ingestBatch() {
        return ingestBatch;
    }

    public Timer reconciliationDuration() {
        return reconciliationDuration;
    }
}
