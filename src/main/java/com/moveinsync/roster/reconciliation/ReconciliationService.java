package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.config.ReconciliationProperties;
import com.moveinsync.roster.core.brake.BrakeDecision;
import com.moveinsync.roster.core.brake.SafetyBrake;
import com.moveinsync.roster.core.reconciliation.ExportCsv;
import com.moveinsync.roster.core.reconciliation.ExportRow;
import com.moveinsync.roster.deactivation.DeactivationReason;
import com.moveinsync.roster.deactivation.DeactivationTrigger;
import com.moveinsync.roster.deactivation.EmployeeDeactivationService;
import com.moveinsync.roster.domain.EmployeeStatus;
import com.moveinsync.roster.domain.Office;
import com.moveinsync.roster.domain.ReconciliationRun;
import com.moveinsync.roster.domain.ReconciliationStatus;
import com.moveinsync.roster.employee.EmployeeRepository;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.office.OfficeRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.web.ConflictException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String ONE_OPEN_RUN_INDEX = "uq_reconciliation_one_open_run";
    private static final String EMPLOYEE_CONSTRAINT = "uq_employee_tenant_external";
    private static final int WRITE_BATCH_SIZE = 500;
    private static final int MAX_CHUNK_ATTEMPTS = 3;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration ABANDONED_AFTER = HEARTBEAT_INTERVAL.multipliedBy(10);
    private static final String RUN_ID_MDC_KEY = "runId";
    private static final String ABORTED_BY_OPERATOR = "REJECTED_BY_OPERATOR";
    private static final String FAILED_INTERNALLY = "INTERNAL_ERROR";

    private final ReconciliationRuns runs;
    private final ReconciliationStore store;
    private final BucketDiffer differ;
    private final ReconciliationApplier applier;
    private final EmployeeRepository employees;
    private final OfficeRepository offices;
    private final EmployeeDeactivationService deactivations;
    private final ReconciliationProperties properties;
    private final RosterMetrics metrics;

    public ReconciliationService(ReconciliationRuns runs, ReconciliationStore store, BucketDiffer differ,
                                 ReconciliationApplier applier, EmployeeRepository employees, OfficeRepository offices,
                                 EmployeeDeactivationService deactivations, ReconciliationProperties properties,
                                 RosterMetrics metrics) {
        this.runs = runs;
        this.store = store;
        this.differ = differ;
        this.applier = applier;
        this.employees = employees;
        this.offices = offices;
        this.deactivations = deactivations;
        this.properties = properties;
        this.metrics = metrics;
    }

    private RunHeartbeat heartbeatFor(long tenantId, long runId) {
        return new RunHeartbeat(() -> runs.heartbeat(tenantId, runId), HEARTBEAT_INTERVAL.toNanos(), System::nanoTime);
    }

    public RunReport start(InputStreamSource export, Instant generatedAt) {
        long tenantId = TenantContext.require();
        requireValidHeader(export);
        long runId = openRun(tenantId, generatedAt);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RUN_ID_MDC_KEY, String.valueOf(runId));
             SpillWorkspace spill = SpillWorkspace.create(properties.bucketCount());
             BufferedReader reader = open(export)) {
            reader.readLine();
            return metrics.reconciliationDuration().recordCallable(() -> execute(runId, tenantId, generatedAt, reader, spill));
        } catch (Exception e) {
            log.error("RECONCILIATION_FAILED runId={} tenantId={} cause={}", runId, tenantId, e.getClass().getName());
            store.discard(runId);
            runs.update(tenantId, runId, run -> {
                run.setStatus(ReconciliationStatus.FAILED);
                run.setFinishedAt(Instant.now());
                run.setAbortReason(FAILED_INTERNALLY);
            });
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Reconciliation run " + runId + " failed", e);
        }
    }

    private RunReport execute(long runId, long tenantId, Instant generatedAt, BufferedReader reader, SpillWorkspace spill) throws IOException {
        long started = System.nanoTime();
        RunHeartbeat heartbeat = heartbeatFor(tenantId, runId);
        Map<String, Long> officeIds = offices.findByTenantIdAndActiveTrue(tenantId).stream()
                .collect(Collectors.toMap(Office::getCode, Office::getId));

        ExportPartitioner.Result partitioned = ExportPartitioner.partition(reader, spill, officeIds.keySet(), properties.bucketCount());
        long partitionedAt = System.nanoTime();

        long activeBefore = employees.countByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE);
        BucketDiffer.Counts diff = differ.diff(runId, tenantId, generatedAt, properties.bucketCount(), properties.pageSize(), spill, heartbeat);
        long diffedAt = System.nanoTime();

        BrakeDecision brake = SafetyBrake.evaluate(diff.candidates, activeBefore, properties.missingThresholdPercent());
        runs.update(tenantId, runId, run -> {
            run.setExportRowCount(partitioned.rows());
            run.setMalformedRowCount(partitioned.malformed() + diff.duplicates);
            run.setUnknownOfficeCount(partitioned.unknownOffice());
            run.setActiveCountBefore(Math.toIntExact(activeBefore));
            run.setMissingCount(diff.candidates);
            run.setMissingPercent(brake.missingPercent());
            run.setUnchangedCount(diff.unchanged);
            run.setSkippedCount(diff.skipped);
        });

        applyChanges(runId, tenantId, generatedAt, officeIds, spill, heartbeat);
        ReconciliationRun finished;
        if (brake.tripped()) {
            finished = runs.update(tenantId, runId, run -> run.setStatus(ReconciliationStatus.AWAITING_APPROVAL));
            metrics.brakeTripped();
            log.error("RECONCILIATION_BRAKE_TRIPPED runId={} tenantId={} activeBefore={} missing={} missingPercent={} thresholdPercent={} exportRows={}",
                    runId, tenantId, activeBefore, diff.candidates, brake.missingPercent(), properties.missingThresholdPercent(), partitioned.rows());
        } else {
            Deactivated deactivated = deactivateParked(runId, generatedAt, heartbeat);
            store.discard(runId);
            finished = runs.update(tenantId, runId, run -> {
                run.setStatus(ReconciliationStatus.COMPLETED);
                run.setFinishedAt(Instant.now());
                run.setDeactivatedCount(deactivated.count());
                run.setSkippedCount(run.getSkippedCount() + deactivated.skipped());
            });
        }
        log.info("Reconciliation run {} tenant {} status {}: partition {} ms, diff {} ms, apply {} ms",
                runId, tenantId, finished.getStatus(), millisBetween(started, partitionedAt), millisBetween(partitionedAt, diffedAt),
                millisBetween(diffedAt, System.nanoTime()));
        return RunReport.of(finished);
    }

    private static long millisBetween(long from, long to) {
        return (to - from) / 1_000_000;
    }

    private void requireValidHeader(InputStreamSource export) {
        try (BufferedReader reader = open(export)) {
            if (!ExportCsv.isValidHeader(reader.readLine())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedReader open(InputStreamSource export) throws IOException {
        return new BufferedReader(new InputStreamReader(export.getInputStream(), StandardCharsets.UTF_8));
    }

    private long openRun(long tenantId, Instant generatedAt) {
        int abandoned = runs.failAbandoned(tenantId, ABANDONED_AFTER);
        if (abandoned > 0) {
            log.warn("Marked {} abandoned reconciliation run(s) FAILED for tenant {}", abandoned, tenantId);
        }
        try {
            return runs.open(tenantId, generatedAt, properties.missingThresholdPercent());
        } catch (DataIntegrityViolationException e) {
            if (messageContains(e, ONE_OPEN_RUN_INDEX)) {
                throw new ConflictException();
            }
            throw e;
        }
    }

    private void applyChanges(long runId, long tenantId, Instant generatedAt, Map<String, Long> officeIds, SpillWorkspace spill,
                              Runnable heartbeat) throws IOException {
        int[] totals = new int[4];
        List<ExportRow> chunk = new ArrayList<>(WRITE_BATCH_SIZE);
        try (BufferedReader changes = spill.readChanges()) {
            for (String line = changes.readLine(); line != null; line = changes.readLine()) {
                chunk.add(SpillWorkspace.row(line));
                if (chunk.size() == WRITE_BATCH_SIZE) {
                    heartbeat.run();
                    applyChunk(tenantId, generatedAt, chunk, officeIds, totals);
                    chunk.clear();
                }
            }
        }
        if (!chunk.isEmpty()) {
            applyChunk(tenantId, generatedAt, chunk, officeIds, totals);
        }
        runs.update(tenantId, runId, run -> {
            run.setCreatedCount(totals[0]);
            run.setUpdatedCount(totals[1]);
            run.setReactivatedCount(totals[2]);
            run.setSkippedCount(run.getSkippedCount() + totals[3]);
        });
    }

    private void applyChunk(long tenantId, Instant generatedAt, List<ExportRow> chunk, Map<String, Long> officeIds, int[] totals) {
        for (int attempt = 1; ; attempt++) {
            try {
                ReconciliationApplier.ChunkResult result = applier.applyChunk(tenantId, generatedAt, chunk, officeIds, properties.bucketCount());
                totals[0] += result.created();
                totals[1] += result.updated();
                totals[2] += result.reactivated();
                totals[3] += result.skipped();
                return;
            } catch (PessimisticLockingFailureException | DataIntegrityViolationException e) {
                boolean retryable = e instanceof PessimisticLockingFailureException || messageContains(e, EMPLOYEE_CONSTRAINT);
                if (!retryable || attempt >= MAX_CHUNK_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static boolean messageContains(RuntimeException e, String text) {
        Throwable cause = e instanceof DataIntegrityViolationException integrity ? integrity.getMostSpecificCause() : e;
        return String.valueOf(cause.getMessage()).contains(text);
    }

    private record Deactivated(int count, int skipped) {
    }

    private Deactivated deactivateParked(long runId, Instant generatedAt, Runnable heartbeat) {
        int count = 0;
        int skipped = 0;
        long afterId = 0;
        List<ReconciliationStore.ParkedCandidate> page;
        do {
            page = store.page(runId, afterId, WRITE_BATCH_SIZE);
            for (ReconciliationStore.ParkedCandidate candidate : page) {
                afterId = candidate.id();
                heartbeat.run();
                if (candidate.hrUpdatedAt() != null && candidate.hrUpdatedAt().isAfter(generatedAt)) {
                    skipped++;
                } else if (deactivations.deactivate(candidate.employeeId(), DeactivationReason.MISSING_FROM_EXPORT, DeactivationTrigger.RECONCILIATION)) {
                    count++;
                }
            }
        } while (page.size() == WRITE_BATCH_SIZE);
        return new Deactivated(count, skipped);
    }

    public RunReport find(long runId) {
        return RunReport.of(runs.find(TenantContext.require(), runId));
    }

    public RunReport approve(long runId, String operator) {
        long tenantId = TenantContext.require();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RUN_ID_MDC_KEY, String.valueOf(runId))) {
            ReconciliationRun run = claim(tenantId, runId);
            Deactivated deactivated;
            try {
                deactivated = deactivateParked(runId, run.getExportGeneratedAt(), heartbeatFor(tenantId, runId));
            } catch (RuntimeException e) {
                log.error("RECONCILIATION_APPROVAL_FAILED runId={} tenantId={} cause={}", runId, tenantId, e.getClass().getName());
                runs.update(tenantId, runId, r -> r.setStatus(ReconciliationStatus.AWAITING_APPROVAL));
                throw e;
            }
            store.discard(runId);
            log.info("Reconciliation run {} tenant {} approved by {}: {} deactivated, {} skipped", runId, tenantId, operator, deactivated.count(), deactivated.skipped());
            return RunReport.of(runs.update(tenantId, runId, r -> {
                r.setStatus(ReconciliationStatus.COMPLETED);
                r.setApprovedBy(operator);
                r.setApprovedAt(Instant.now());
                r.setFinishedAt(Instant.now());
                r.setDeactivatedCount(deactivated.count());
                r.setSkippedCount(r.getSkippedCount() + deactivated.skipped());
            }));
        }
    }

    public RunReport reject(long runId, String operator) {
        long tenantId = TenantContext.require();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RUN_ID_MDC_KEY, String.valueOf(runId))) {
            claim(tenantId, runId);
            store.discard(runId);
            log.warn("Reconciliation run {} tenant {} rejected by {}: parked deactivations discarded", runId, tenantId, operator);
            return RunReport.of(runs.update(tenantId, runId, r -> {
                r.setStatus(ReconciliationStatus.ABORTED);
                r.setAbortReason(ABORTED_BY_OPERATOR);
                r.setFinishedAt(Instant.now());
            }));
        }
    }

    private ReconciliationRun claim(long tenantId, long runId) {
        ReconciliationRun run = runs.find(tenantId, runId);
        if (!runs.claimForResolution(tenantId, runId)) {
            throw new ConflictException();
        }
        return run;
    }
}
