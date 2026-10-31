package com.moveinsync.roster.web;

public class ConflictException extends RuntimeException {

    public ConflictException() {
        super("Conflict with the current state");
    }
}
