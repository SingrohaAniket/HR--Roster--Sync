package com.moveinsync.roster.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roster.reconciliation")
public record ReconciliationProperties(
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal missingThresholdPercent,
        @Positive int pageSize,
        @Positive @Max(65536) int bucketCount) {
}
