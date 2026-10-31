package com.moveinsync.roster.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roster.ingest")
public record IngestProperties(@Positive int maxBatchSize) {
}
