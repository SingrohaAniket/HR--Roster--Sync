package com.moveinsync.roster.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.config.IngestProperties;
import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.ownership.Owner;
import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.core.validation.ValidatedField;
import com.moveinsync.roster.core.validation.ValidationError;
import com.moveinsync.roster.domain.EventOutcome;
import com.moveinsync.roster.domain.Office;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.office.OfficeDirectory;
import com.moveinsync.roster.security.TenantContext;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final String EMPLOYEE_CONSTRAINT = "uq_employee_tenant_external";
    private static final int MAX_ATTEMPTS = 5;
    private static final int MIN_RETRY_WAIT_MILLIS = 10;
    private static final int MAX_RETRY_WAIT_MILLIS = 80;

    private static final int MAX_CONSECUTIVE_INFRASTRUCTURE_FAILURES = 3;

    private final EventApplier applier;
    private final RosterEventRepository events;
    private final OfficeDirectory offices;
    private final IngestProperties properties;
    private final RosterMetrics metrics;

    public IngestService(EventApplier applier, RosterEventRepository events, OfficeDirectory offices,
                         IngestProperties properties, RosterMetrics metrics) {
        this.applier = applier;
        this.events = events;
        this.offices = offices;
        this.properties = properties;
        this.metrics = metrics;
    }

    public IngestResponse ingest(List<JsonNode> rawEvents) {
        if (rawEvents.size() > properties.maxBatchSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return metrics.ingestBatch().record(() -> process(rawEvents));
    }

    private IngestResponse process(List<JsonNode> rawEvents) {
        List<EventResult> results = new ArrayList<>(rawEvents.size());
        int consecutiveInfrastructureFailures = 0;
        for (int index = 0; index < rawEvents.size(); index++) {
            IngestEvent event = IngestEvent.parse(rawEvents.get(index));
            if (consecutiveInfrastructureFailures >= MAX_CONSECUTIVE_INFRASTRUCTURE_FAILURES) {
                results.add(notAttempted(index, event));
                continue;
            }
            Processed processed = process(index, event);
            results.add(processed.result());
            switch (processed.databaseContact()) {
                case FAILED -> consecutiveInfrastructureFailures++;
                case SUCCEEDED -> consecutiveInfrastructureFailures = 0;
                case NONE -> { }
            }
        }
        if (consecutiveInfrastructureFailures >= MAX_CONSECUTIVE_INFRASTRUCTURE_FAILURES) {
            log.error("Abandoned an ingest batch of {} records after {} consecutive infrastructure failures",
                    rawEvents.size(), consecutiveInfrastructureFailures);
        }
        results.forEach(this::count);
        return IngestResponse.of(results);
    }

    private void count(EventResult result) {
        metrics.ingestEvent(result.outcome());
        result.errors().forEach(error -> metrics.ingestRejection(error.getCode()));
    }

    private static EventResult notAttempted(int index, IngestEvent event) {
        List<ValidationError> errors = event.validate();
        return errors.isEmpty()
                ? result(index, event, EventOutcome.NOT_PROCESSED, null, List.of(), List.of())
                : rejected(index, event, errors);
    }

    private Processed process(int index, IngestEvent event) {
        List<ValidationError> errors = event.validate();
        if (!errors.isEmpty()) {
            return new Processed(rejected(index, event, errors), DatabaseContact.NONE);
        }

        Optional<Office> office = Optional.empty();
        if (carriesOwnedOfficeCode(event)) {
            try {
                office = offices.findActive(TenantContext.require(), event.text(FieldKey.OFFICE_CODE));
            } catch (RuntimeException e) {
                return failed(index, event, e);
            }
            if (office.isEmpty()) {
                return new Processed(rejected(index, event, List.of(new ValidationError(ErrorCode.UNKNOWN_OFFICE, ValidatedField.OFFICE_CODE))),
                        DatabaseContact.SUCCEEDED);
            }
        }
        return applyWithRetry(index, event, office.orElse(null));
    }

    private static boolean carriesOwnedOfficeCode(IngestEvent event) {
        return Owner.HR.name().equals(event.source()) && event.text(FieldKey.OFFICE_CODE) != null;
    }

    private Processed applyWithRetry(int index, IngestEvent event, Office office) {
        for (int attempt = 1; ; attempt++) {
            try {
                ApplyResult applied = applier.apply(event, office);
                return new Processed(result(index, event, applied.outcome(), null, List.of(), applied.warnings()),
                        DatabaseContact.SUCCEEDED);
            } catch (DuplicateEventException e) {
                return duplicate(index, event);
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e) || !waitBeforeRetry()) {
                    return failed(index, event, e);
                }
            }
        }
    }

    private static boolean waitBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_RETRY_WAIT_MILLIS, MAX_RETRY_WAIT_MILLIS + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Processed failed(int index, IngestEvent event, RuntimeException e) {
        StackTraceElement[] stack = e.getStackTrace();
        log.error("Could not process event {} for employee {}: {} at {}", event.eventId(),
                event.employeeExternalId(), e.getClass().getName(), stack.length > 0 ? stack[0] : "unknown");
        return new Processed(rejected(index, event, List.of(new ValidationError(ErrorCode.PROCESSING_FAILED, ValidatedField.EVENT_ID))),
                isInfrastructureFailure(e) ? DatabaseContact.FAILED : DatabaseContact.SUCCEEDED);
    }

    private static boolean isRetryable(RuntimeException e) {
        return e instanceof PessimisticLockingFailureException
                || e instanceof DataIntegrityViolationException integrity
                && String.valueOf(integrity.getMostSpecificCause().getMessage()).contains(EMPLOYEE_CONSTRAINT);
    }

    private static boolean isInfrastructureFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof QueryTimeoutException
                    || t instanceof SQLTransientConnectionException
                    || t instanceof SQLNonTransientConnectionException
                    || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t.getCause() == t) {
                return false;
            }
        }
        return false;
    }

    private Processed duplicate(int index, IngestEvent event) {
        RosterEvent first;
        try {
            first = events.findByTenantIdAndIdempotencyKey(TenantContext.require(), event.eventId())
                    .orElseThrow(() -> new IllegalStateException("A claimed idempotency key has no ledger row"));
        } catch (RuntimeException e) {
            return failed(index, event, e);
        }
        List<ValidationError> warnings = first.getPayloadHash().equals(event.payloadHash())
                ? List.of()
                : List.of(new ValidationError(ErrorCode.IDEMPOTENCY_KEY_REUSED, ValidatedField.EVENT_ID));
        return new Processed(result(index, event, EventOutcome.DUPLICATE_NOOP, first.getOutcome(), List.of(), warnings),
                DatabaseContact.SUCCEEDED);
    }

    private static EventResult rejected(int index, IngestEvent event, List<ValidationError> errors) {
        return result(index, event, EventOutcome.REJECTED, null, errors, List.of());
    }

    private static EventResult result(int index, IngestEvent event, EventOutcome outcome, EventOutcome originalOutcome,
                                      List<ValidationError> errors, List<ValidationError> warnings) {
        String eventId = hasError(errors, ErrorCode.MISSING_EVENT_ID) ? null : event.eventId();
        String externalId = hasError(errors, ErrorCode.MISSING_EXTERNAL_ID) ? null : event.employeeExternalId();
        return EventResult.of(index, eventId, externalId, outcome, originalOutcome, errors, warnings);
    }

    private static boolean hasError(List<ValidationError> errors, ErrorCode code) {
        return errors.stream().anyMatch(error -> error.code() == code);
    }

    private enum DatabaseContact { NONE, SUCCEEDED, FAILED }

    private record Processed(EventResult result, DatabaseContact databaseContact) {
    }
}
