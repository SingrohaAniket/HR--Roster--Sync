package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.domain.ReconciliationRun;
import com.moveinsync.roster.web.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReconciliationRuns {

    private final ReconciliationRunRepository runs;

    ReconciliationRuns(ReconciliationRunRepository runs) {
        this.runs = runs;
    }

    @Transactional
    public long open(long tenantId, Instant generatedAt, BigDecimal thresholdPercent) {
        return runs.save(new ReconciliationRun(tenantId, generatedAt, thresholdPercent)).getId();
    }

    @Transactional
    public int failAbandoned(long tenantId, Duration staleAfter) {
        return runs.failAbandoned(tenantId, staleAfter.toSeconds());
    }

    @Transactional
    public void heartbeat(long tenantId, long runId) {
        runs.heartbeat(tenantId, runId);
    }

    @Transactional(readOnly = true)
    public ReconciliationRun find(long tenantId, long runId) {
        return runs.findByTenantIdAndId(tenantId, runId).orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional
    public ReconciliationRun update(long tenantId, long runId, Consumer<ReconciliationRun> change) {
        ReconciliationRun run = find(tenantId, runId);
        change.accept(run);
        return run;
    }

    @Transactional
    public boolean claimForResolution(long tenantId, long runId) {
        return runs.claimForResolution(tenantId, runId) == 1;
    }
}
