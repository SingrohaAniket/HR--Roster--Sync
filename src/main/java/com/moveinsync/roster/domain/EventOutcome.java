package com.moveinsync.roster.domain;

public enum EventOutcome {
    APPLIED,
    IGNORED_STALE,
    DUPLICATE_NOOP,
    IGNORED_NO_OWNED_FIELDS,
    REJECTED,
    NOT_PROCESSED
}
