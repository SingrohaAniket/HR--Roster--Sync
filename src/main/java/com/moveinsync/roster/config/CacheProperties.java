package com.moveinsync.roster.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roster.cache")
public record CacheProperties(
        @NotNull @DurationMin(seconds = 1) Duration officeTtl,
        @Positive long officeMaxSize) {
}
