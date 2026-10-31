package com.moveinsync.roster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun extends BaseEntity {

    private Long tenantId;

    @CreationTimestamp
    private Instant startedAt;

    private Instant finishedAt;

    @Column(updatable = false)
    private Instant lastProgressAt = Instant.now();

    @Enumerated(EnumType.STRING)
    private ReconciliationStatus status = ReconciliationStatus.RUNNING;

    private Instant exportGeneratedAt;

    private int exportRowCount;

    private int activeCountBefore;

    private int missingCount;

    private BigDecimal missingPercent = BigDecimal.ZERO;

    private BigDecimal thresholdPercent;

    private int createdCount;

    private int updatedCount;

    private int deactivatedCount;

    private int reactivatedCount;

    private int skippedCount;

    private int unchangedCount;

    private int malformedRowCount;

    private int unknownOfficeCount;

    private String abortReason;

    private String approvedBy;

    private Instant approvedAt;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(Long tenantId, Instant exportGeneratedAt, BigDecimal thresholdPercent) {
        this.tenantId = tenantId;
        this.exportGeneratedAt = exportGeneratedAt;
        this.thresholdPercent = thresholdPercent;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastProgressAt() {
        return lastProgressAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public void setStatus(ReconciliationStatus status) {
        this.status = status;
    }

    public Instant getExportGeneratedAt() {
        return exportGeneratedAt;
    }

    public int getExportRowCount() {
        return exportRowCount;
    }

    public void setExportRowCount(int exportRowCount) {
        this.exportRowCount = exportRowCount;
    }

    public int getActiveCountBefore() {
        return activeCountBefore;
    }

    public void setActiveCountBefore(int activeCountBefore) {
        this.activeCountBefore = activeCountBefore;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public void setMissingCount(int missingCount) {
        this.missingCount = missingCount;
    }

    public BigDecimal getMissingPercent() {
        return missingPercent;
    }

    public void setMissingPercent(BigDecimal missingPercent) {
        this.missingPercent = missingPercent;
    }

    public BigDecimal getThresholdPercent() {
        return thresholdPercent;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public void setCreatedCount(int createdCount) {
        this.createdCount = createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public void setUpdatedCount(int updatedCount) {
        this.updatedCount = updatedCount;
    }

    public int getDeactivatedCount() {
        return deactivatedCount;
    }

    public void setDeactivatedCount(int deactivatedCount) {
        this.deactivatedCount = deactivatedCount;
    }

    public int getReactivatedCount() {
        return reactivatedCount;
    }

    public void setReactivatedCount(int reactivatedCount) {
        this.reactivatedCount = reactivatedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public int getUnchangedCount() {
        return unchangedCount;
    }

    public void setUnchangedCount(int unchangedCount) {
        this.unchangedCount = unchangedCount;
    }

    public int getMalformedRowCount() {
        return malformedRowCount;
    }

    public void setMalformedRowCount(int malformedRowCount) {
        this.malformedRowCount = malformedRowCount;
    }

    public int getUnknownOfficeCount() {
        return unknownOfficeCount;
    }

    public void setUnknownOfficeCount(int unknownOfficeCount) {
        this.unknownOfficeCount = unknownOfficeCount;
    }

    public String getAbortReason() {
        return abortReason;
    }

    public void setAbortReason(String abortReason) {
        this.abortReason = abortReason;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }
}
