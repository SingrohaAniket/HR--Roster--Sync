package com.moveinsync.roster.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roster.outbox")
public record OutboxProperties(
        @Positive long pollIntervalMs,
        @Positive int batchSize,
        @Positive int maxAttempts,
        @Positive int leaseSeconds,
        @Positive long confirmTimeoutMs) {
}
