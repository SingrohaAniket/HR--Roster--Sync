package com.moveinsync.roster.core.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.roster.core.reconciliation.DiffClassifier.StoreRow;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DiffClassifierTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant BEFORE = GENERATED_AT.minusSeconds(60);
    private static final Instant AFTER = GENERATED_AT.plusMillis(1);

    private static StoreRow active(String hash) {
        return new StoreRow(true, hash, BEFORE);
    }

    private static StoreRow inactive(String hash) {
        return new StoreRow(false, hash, BEFORE);
    }

    private static DiffAction classify(boolean inExport, String exportHash, StoreRow store) {
        return DiffClassifier.classify(inExport, exportHash, store, GENERATED_AT);
    }

    @Test
    void inExportAbsentFromStoreIsACreate() {
        assertEquals(DiffAction.CREATE, classify(true, "h", null));
    }

    @Test
    void inExportActiveSameHashIsUnchanged() {
        assertEquals(DiffAction.UNCHANGED, classify(true, "h", active("h")));
    }

    @Test
    void inExportActiveDifferentHashIsAnUpdate() {
        assertEquals(DiffAction.UPDATE, classify(true, "new", active("old")));
    }

    @Test
    void inExportInactiveIsARehireEvenWithTheSameHash() {
        assertEquals(DiffAction.REACTIVATE, classify(true, "h", inactive("h")));
    }

    @Test
    void absentFromExportActiveIsADeactivationCandidate() {
        assertEquals(DiffAction.DEACTIVATION_CANDIDATE, classify(false, null, active("h")));
    }

    @Test
    void absentFromExportInactiveIsUnchanged() {
        assertEquals(DiffAction.UNCHANGED, classify(false, null, inactive("h")));
    }

    @Test
    void anEmployeeChangedAfterTheSnapshotIsSkippedWhatTheFileSays() {
        StoreRow live = new StoreRow(true, "live", AFTER);

        assertEquals(DiffAction.SKIPPED_NEWER_THAN_EXPORT, classify(true, "old", live));
        assertEquals(DiffAction.SKIPPED_NEWER_THAN_EXPORT, classify(true, "live", live));
        assertEquals(DiffAction.SKIPPED_NEWER_THAN_EXPORT, classify(false, null, live), "joined after the snapshot, so not missing");
        assertEquals(DiffAction.SKIPPED_NEWER_THAN_EXPORT, classify(true, null, live));
    }

    @Test
    void aRowUpdatedExactlyAtTheSnapshotIsNotLater() {
        assertEquals(DiffAction.UPDATE, classify(true, "new", new StoreRow(true, "old", GENERATED_AT)));
    }

    @Test
    void anEmployeeHrHasNeverWrittenIsNotSkipped() {
        assertEquals(DiffAction.UPDATE, classify(true, "new", new StoreRow(true, "empty", null)));
    }

    @Test
    void aNamedButUnusableRowIsNeverACandidate() {
        assertEquals(DiffAction.UNUSABLE_ROW, classify(true, null, active("h")));
        assertEquals(DiffAction.UNUSABLE_ROW, classify(true, null, inactive("h")));
        assertEquals(DiffAction.UNUSABLE_ROW, classify(true, null, null));
    }
}
