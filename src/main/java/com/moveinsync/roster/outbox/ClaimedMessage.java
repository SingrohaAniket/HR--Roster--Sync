package com.moveinsync.roster.outbox;

public record ClaimedMessage(long id, String eventType, String payload, int attempts) {
}
