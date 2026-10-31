package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.Role;
import java.time.Instant;

public record JwtClaims(
        String username,
        long tenantId,
        String tenantCode,
        Role role,
        String employeeExternalId,
        Instant issuedAt,
        Instant expiresAt) {
}
