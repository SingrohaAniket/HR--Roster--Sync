package com.moveinsync.roster.ingest;

import com.moveinsync.roster.config.ReconciliationProperties;
import com.moveinsync.roster.core.hashing.BucketAssigner;
import com.moveinsync.roster.core.hashing.ContentHasher;
import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.ownership.FieldOwnership;
import com.moveinsync.roster.core.ownership.Owner;
import com.moveinsync.roster.core.ownership.OwnershipSplit;
import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.core.validation.ValidatedField;
import com.moveinsync.roster.core.validation.ValidationError;
import com.moveinsync.roster.core.version.VersionDecision;
import com.moveinsync.roster.core.version.VersionResolver;
import com.moveinsync.roster.deactivation.DeactivationReason;
import com.moveinsync.roster.deactivation.DeactivationTrigger;
import com.moveinsync.roster.deactivation.EmployeeDeactivationService;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;
import com.moveinsync.roster.domain.EventOutcome;
import com.moveinsync.roster.domain.Office;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.domain.RosterEventType;
import com.moveinsync.roster.domain.SourceSystem;
import com.moveinsync.roster.employee.EmployeeRepository;
import com.moveinsync.roster.outbox.OutboxEventType;
import com.moveinsync.roster.outbox.OutboxWriter;
import com.moveinsync.roster.security.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventApplier {

    private static final Logger log = LoggerFactory.getLogger(EventApplier.class);
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_roster_event_tenant_key";

    private final RosterEventRepository events;
    private final EmployeeRepository employees;
    private final OutboxWriter outboxWriter;
    private final EmployeeDeactivationService deactivations;
    private final ReconciliationProperties reconciliation;

    public EventApplier(RosterEventRepository events, EmployeeRepository employees, OutboxWriter outboxWriter,
                        EmployeeDeactivationService deactivations, ReconciliationProperties reconciliation) {
        this.events = events;
        this.employees = employees;
        this.outboxWriter = outboxWriter;
        this.deactivations = deactivations;
        this.reconciliation = reconciliation;
    }

    @Transactional
    public ApplyResult apply(IngestEvent event, Office office) {
        RosterEvent ledgerRow = claimIdempotencyKey(event);
        ApplyResult result = applyToEmployee(event, office);
        ledgerRow.setOutcome(result.outcome());
        ledgerRow.setProcessedAt(Instant.now());
        return result;
    }

    private RosterEvent claimIdempotencyKey(IngestEvent event) {
        try {
            return events.save(new RosterEvent(TenantContext.require(), event.eventId(), event.employeeExternalId(),
                    SourceSystem.valueOf(event.source()), RosterEventType.valueOf(event.eventType()),
                    event.version(), event.payloadHash()));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(IDEMPOTENCY_CONSTRAINT)) {
                throw new DuplicateEventException();
            }
            throw e;
        }
    }

    private ApplyResult applyToEmployee(IngestEvent event, Office office) {
        long tenantId = TenantContext.require();
        Optional<Employee> existing = employees.findForUpdateByTenantIdAndExternalId(tenantId, event.employeeExternalId());
        boolean isNew = existing.isEmpty();
        if (isNew && !event.isCreate()) {
            log.info("No CREATE was seen for employee {}; upserting from an {} event",
                    event.employeeExternalId(), event.eventType());
        }
        Employee employee = existing.orElseGet(() -> newEmployee(tenantId, event.employeeExternalId()));

        Owner sender = Owner.valueOf(event.source());
        long storedVersion = sender == Owner.HR ? employee.getHrVersion() : employee.getTransportVersion();
        VersionDecision decision = VersionResolver.resolve(event.version(), storedVersion);
        if (decision == VersionDecision.STALE) {
            return ApplyResult.of(EventOutcome.IGNORED_STALE);
        }
        if (decision == VersionDecision.DUPLICATE) {
            return ApplyResult.of(EventOutcome.DUPLICATE_NOOP);
        }

        OwnershipSplit split = FieldOwnership.split(sender, event.presentFields());
        List<ValidationError> warnings = split.dropped().stream()
                .map(field -> new ValidationError(ErrorCode.FIELD_IGNORED_NOT_OWNED, ValidatedField.of(field)))
                .toList();
        if (split.toWrite().isEmpty()) {
            return new ApplyResult(EventOutcome.IGNORED_NO_OWNED_FIELDS, warnings);
        }

        Instant now = Instant.now();
        if (event.isDeactivate()) {
            return deactivate(employee, isNew, event, now);
        }

        Set<FieldKey> changed = writeFields(employee, split.toWrite(), event, office);
        if (changed.stream().anyMatch(field -> FieldOwnership.ownerOf(field) == Owner.HR)) {
            employee.setContentHash(hashOf(employee));
        }
        if (sender == Owner.HR && event.isCreate() && employee.getStatus() == EmployeeStatus.INACTIVE) {
            employee.setStatus(EmployeeStatus.ACTIVE);
            employee.setDeactivatedAt(null);
            changed.add(FieldKey.STATUS);
        }
        advanceSenderVersion(employee, sender, event.version(), now);
        if (isNew) {
            employees.save(employee);
        }
        outboxWriter.write(employee, isNew ? OutboxEventType.EMPLOYEE_CREATED : OutboxEventType.EMPLOYEE_UPDATED,
                changed, occurredAtOrNow(event, now));
        return new ApplyResult(EventOutcome.APPLIED, warnings);
    }

    private ApplyResult deactivate(Employee employee, boolean isNew, IngestEvent event, Instant now) {
        if (!isNew && employee.getStatus() == EmployeeStatus.INACTIVE) {
            return ApplyResult.of(EventOutcome.DUPLICATE_NOOP);
        }
        advanceSenderVersion(employee, Owner.HR, event.version(), now);
        if (isNew) {
            employees.save(employee);
        }
        deactivations.deactivate(employee.getId(), DeactivationReason.EMPLOYEE_EXIT, DeactivationTrigger.INGEST_EVENT);
        return ApplyResult.of(EventOutcome.APPLIED);
    }

    private Employee newEmployee(long tenantId, String externalId) {
        return new Employee(tenantId, externalId, ContentHasher.hash(null, null, null, null, null, null),
                BucketAssigner.bucket(externalId, reconciliation.bucketCount()));
    }

    private static void advanceSenderVersion(Employee employee, Owner sender, long version, Instant now) {
        if (sender == Owner.HR) {
            employee.setHrVersion(version);
            employee.setHrUpdatedAt(now);
        } else {
            employee.setTransportVersion(version);
            employee.setTransportUpdatedAt(now);
        }
    }

    private static Instant occurredAtOrNow(IngestEvent event, Instant now) {
        return event.occurredAt() == null ? now : event.occurredAt();
    }

    private static String hashOf(Employee employee) {
        return ContentHasher.hash(employee.getName(), employee.getEmail(), employee.getPhone(),
                employee.getManagerExternalId(),
                employee.getOffice() == null ? null : employee.getOffice().getCode(),
                employee.getShiftCode());
    }

    private static Set<FieldKey> writeFields(Employee e, Set<FieldKey> toWrite, IngestEvent event, Office office) {
        Set<FieldKey> changed = EnumSet.noneOf(FieldKey.class);
        for (FieldKey field : toWrite) {
            switch (field) {
                case NAME -> write(field, e.getName(), event.text(field), e::setName, changed);
                case EMAIL -> write(field, e.getEmail(), event.text(field), e::setEmail, changed);
                case PHONE -> write(field, e.getPhone(), event.text(field), e::setPhone, changed);
                case MANAGER_EXTERNAL_ID -> write(field, e.getManagerExternalId(), event.text(field), e::setManagerExternalId, changed);
                case OFFICE_CODE -> write(field, e.getOffice(), office, e::setOffice, changed);
                case SHIFT_CODE -> write(field, e.getShiftCode(), event.text(field), e::setShiftCode, changed);
                case PICKUP_LAT -> write(field, e.getPickupLat(), event.decimal(field), e::setPickupLat, changed);
                case PICKUP_LNG -> write(field, e.getPickupLng(), event.decimal(field), e::setPickupLng, changed);
                case PICKUP_LABEL -> write(field, e.getPickupLabel(), event.text(field), e::setPickupLabel, changed);
                case SPECIAL_ASSISTANCE -> write(field, e.getSpecialAssistance(), event.text(field), e::setSpecialAssistance, changed);
                case STATUS -> { }
            }
        }
        return changed;
    }

    private static <T> void write(FieldKey field, T current, T next, Consumer<T> setter, Set<FieldKey> changed) {
        if (!isSame(current, next)) {
            setter.accept(next);
            changed.add(field);
        }
    }

    private static boolean isSame(Object current, Object next) {
        if (current instanceof BigDecimal a && next instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        return Objects.equals(current, next);
    }
}
