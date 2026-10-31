package com.moveinsync.roster.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roster.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32) String secret,
        @NotNull @DurationMin(seconds = 1) Duration accessTokenTtl) {
}
