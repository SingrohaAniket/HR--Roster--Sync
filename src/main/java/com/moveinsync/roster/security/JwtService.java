package com.moveinsync.roster.security;

import com.moveinsync.roster.config.JwtProperties;
import com.moveinsync.roster.domain.AppUser;
import com.moveinsync.roster.domain.Role;
import com.moveinsync.roster.domain.Tenant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_TENANT_CODE = "tenantCode";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMPLOYEE_EXTERNAL_ID = "employeeExternalId";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = properties.accessTokenTtl();
    }

    public String issue(AppUser user, Tenant tenant) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_TENANT_ID, user.getTenantId())
                .claim(CLAIM_TENANT_CODE, tenant.getCode())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (user.getRole() == Role.EMPLOYEE) {
            builder.claim(CLAIM_EMPLOYEE_EXTERNAL_ID, user.getEmployeeExternalId());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    public JwtClaims parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

            String username = required(claims.getSubject());
            Number tenantId = required(claims.get(CLAIM_TENANT_ID, Number.class));
            String tenantCode = required(claims.get(CLAIM_TENANT_CODE, String.class));
            Role role = Role.valueOf(required(claims.get(CLAIM_ROLE, String.class)));
            Date issuedAt = required(claims.getIssuedAt());
            Date expiresAt = required(claims.getExpiration());
            String employeeExternalId = claims.get(CLAIM_EMPLOYEE_EXTERNAL_ID, String.class);
            if ((role == Role.EMPLOYEE) != (employeeExternalId != null)) {
                throw new InvalidTokenException();
            }

            return new JwtClaims(username, tenantId.longValue(), tenantCode, role, employeeExternalId,
                    issuedAt.toInstant(), expiresAt.toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    private static <T> T required(T claim) {
        if (claim == null) {
            throw new InvalidTokenException();
        }
        return claim;
    }
}
