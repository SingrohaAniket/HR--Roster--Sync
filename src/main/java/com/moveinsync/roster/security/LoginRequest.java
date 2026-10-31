package com.moveinsync.roster.security;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String tenantCode,
        @NotBlank String username,
        @NotBlank String password) {

    @Override
    public String toString() {
        return "LoginRequest[tenantCode=" + tenantCode + ", username=" + username + "]";
    }
}
