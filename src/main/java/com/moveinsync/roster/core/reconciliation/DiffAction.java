package com.moveinsync.roster.core.reconciliation;

public enum DiffAction {
    CREATE,
    UPDATE,
    REACTIVATE,
    UNCHANGED,
    DEACTIVATION_CANDIDATE,
    SKIPPED_NEWER_THAN_EXPORT,
    UNUSABLE_ROW
}
