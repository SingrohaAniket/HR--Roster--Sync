package com.moveinsync.roster.core.reconciliation;

import java.time.Instant;

public final class DiffClassifier {

    private DiffClassifier() {
    }

    public record StoreRow(boolean active, String contentHash, Instant hrUpdatedAt) {
    }

    public static DiffAction classify(boolean inExport, String exportHash, StoreRow store, Instant generatedAt) {
        if (store != null && store.hrUpdatedAt() != null && store.hrUpdatedAt().isAfter(generatedAt)) {
            return DiffAction.SKIPPED_NEWER_THAN_EXPORT;
        }
        if (inExport && exportHash == null) {
            return DiffAction.UNUSABLE_ROW;
        }
        if (store == null) {
            return DiffAction.CREATE;
        }
        if (!inExport) {
            return store.active() ? DiffAction.DEACTIVATION_CANDIDATE : DiffAction.UNCHANGED;
        }
        if (!store.active()) {
            return DiffAction.REACTIVATE;
        }
        return exportHash.equals(store.contentHash()) ? DiffAction.UNCHANGED : DiffAction.UPDATE;
    }
}
