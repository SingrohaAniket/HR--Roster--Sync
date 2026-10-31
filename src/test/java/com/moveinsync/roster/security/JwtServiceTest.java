package com.moveinsync.roster.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.roster.config.JwtProperties;
import com.moveinsync.roster.domain.AppUser;
import com.moveinsync.roster.domain.Role;
import com.moveinsync.roster.domain.Tenant;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final Tenant ACME = new Tenant("ACME", "Acme Demo Corp");

    private final JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofHours(1)));

    private static AppUser employee() {
        return new AppUser(1L, "acme.employee", "hash", Role.EMPLOYEE, "EMP-1002");
    }

    private static AppUser admin() {
        return new AppUser(1L, "acme.admin", "hash", Role.ADMIN, null);
    }

    private static JwtBuilder validBuilder() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("acme.operator")
                .claim("tenantId", 1L)
                .claim("tenantCode", "ACME")
                .claim("role", "OPERATOR")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1))));
    }

    private static String sign(JwtBuilder builder) {
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private void assertRejected(String token) {
        assertThrows(InvalidTokenException.class, () -> service.parse(token));
    }

    @Test
    void employeeTokenRoundTripsEveryClaim() {
        JwtClaims claims = service.parse(service.issue(employee(), ACME));

        assertEquals("acme.employee", claims.username());
        assertEquals(1L, claims.tenantId());
        assertEquals("ACME", claims.tenantCode());
        assertEquals(Role.EMPLOYEE, claims.role());
        assertEquals("EMP-1002", claims.employeeExternalId());
        assertEquals(Duration.ofHours(1), Duration.between(claims.issuedAt(), claims.expiresAt()));
    }

    @Test
    void nonEmployeeTokenHasNoEmployeeClaim() {
        JwtClaims claims = service.parse(service.issue(admin(), ACME));

        assertEquals(Role.ADMIN, claims.role());
        assertNull(claims.employeeExternalId());
    }

    @Test
    void aLargeTenantIdSurvivesTheRoundTrip() {
        AppUser user = new AppUser(5_000_000_000L, "u", "hash", Role.ADMIN, null);

        assertEquals(5_000_000_000L, service.parse(service.issue(user, ACME)).tenantId());
    }

    @Test
    void tokenIsSignedWithHs256() {
        String header = service.issue(admin(), ACME).split("\\.")[0];

        assertTrue(new String(Base64.getUrlDecoder().decode(header), StandardCharsets.UTF_8).contains("\"HS256\""));
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = service.issue(admin(), ACME).split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"tenantId\":1", "\"tenantId\":2").getBytes(StandardCharsets.UTF_8));

        assertRejected(parts[0] + "." + forged + "." + parts[2]);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        SecretKey other = Keys.hmacShaKeyFor("another-secret-that-is-at-least-32-bytes-long!".getBytes(StandardCharsets.UTF_8));

        assertRejected(validBuilder().signWith(other, Jwts.SIG.HS256).compact());
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = service.issue(admin(), ACME).split("\\.")[1];

        assertRejected(header + "." + payload + ".");
    }

    @Test
    void expiredTokenIsRejected() {
        assertRejected(sign(validBuilder().expiration(Date.from(Instant.now().minus(Duration.ofMinutes(1))))));
    }

    @Test
    void tokenWithoutAnExpiryIsRejected() {
        assertRejected(sign(validBuilder().expiration(null)));
    }

    @Test
    void tokenWithoutAnIssuedAtIsRejected() {
        assertRejected(sign(validBuilder().issuedAt(null)));
    }

    @Test
    void tokenWithoutASubjectIsRejected() {
        assertRejected(sign(validBuilder().subject(null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenantId", "tenantCode", "role"})
    void tokenMissingARequiredClaimIsRejected(String claim) {
        assertRejected(sign(validBuilder().claims().delete(claim).and()));
    }

    @Test
    void wronglyTypedTenantIdIsRejected() {
        assertRejected(sign(validBuilder().claim("tenantId", "1")));
    }

    @Test
    void unknownRoleIsRejected() {
        assertRejected(sign(validBuilder().claim("role", "SUPERUSER")));
    }

    @Test
    void employeeTokenWithoutItsExternalIdIsRejected() {
        assertRejected(sign(validBuilder().claim("role", "EMPLOYEE")));
    }

    @Test
    void nonEmployeeTokenCarryingAnExternalIdIsRejected() {
        assertRejected(sign(validBuilder().claim("employeeExternalId", "EMP-1001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "a.b", "a.b.c"})
    void malformedTokensAreRejected(String token) {
        assertRejected(token);
    }

    @Test
    void ttlIsReportedInSeconds() {
        assertEquals(3600, service.ttlSeconds());
    }
}
