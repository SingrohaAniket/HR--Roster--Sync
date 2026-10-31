package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.Role;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, Role role) {

    @Override
    public String toString() {
        return "LoginResponse[tokenType=" + tokenType + ", expiresInSeconds=" + expiresInSeconds + ", role=" + role + "]";
    }
}
