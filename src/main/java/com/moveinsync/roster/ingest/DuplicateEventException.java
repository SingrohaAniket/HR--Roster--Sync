package com.moveinsync.roster.ingest;

class DuplicateEventException extends RuntimeException {

    DuplicateEventException() {
        super("Idempotency key already claimed");
    }
}
