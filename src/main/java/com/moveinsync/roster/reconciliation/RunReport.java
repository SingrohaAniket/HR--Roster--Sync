package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.domain.ReconciliationRun;
import com.moveinsync.roster.domain.ReconciliationStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record RunReport(
        long id,
        ReconciliationStatus status,
        Instant exportGeneratedAt,
        Instant startedAt,
        Instant finishedAt,
        int exportRowCount,
        int activeCountBefore,
        int missingCount,
        BigDecimal missingPercent,
        BigDecimal thresholdPercent,
        int createdCount,
        int updatedCount,
        int reactivatedCount,
        int deactivatedCount,
        int skippedCount,
        int unchangedCount,
        int malformedRowCount,
        int unknownOfficeCount,
        String abortReason,
        String approvedBy,
        Instant approvedAt) {

    static RunReport of(ReconciliationRun run) {
        return new RunReport(run.getId(), run.getStatus(), run.getExportGeneratedAt(), run.getStartedAt(), run.getFinishedAt(),
                run.getExportRowCount(), run.getActiveCountBefore(), run.getMissingCount(), run.getMissingPercent(), run.getThresholdPercent(),
                run.getCreatedCount(), run.getUpdatedCount(), run.getReactivatedCount(), run.getDeactivatedCount(),
                run.getSkippedCount(), run.getUnchangedCount(), run.getMalformedRowCount(), run.getUnknownOfficeCount(),
                run.getAbortReason(), run.getApprovedBy(), run.getApprovedAt());
    }
}
