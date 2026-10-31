package com.moveinsync.roster.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.roster.config.JwtProperties;
import com.moveinsync.roster.domain.AppUser;
import com.moveinsync.roster.domain.Role;
import com.moveinsync.roster.domain.Tenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthFilterTest {

    private final JwtService jwtService = new JwtService(
            new JwtProperties("unit-test-secret-that-is-at-least-32-bytes-long", Duration.ofHours(1)));
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private String adminTokenOfTenant(long tenantId) {
        return jwtService.issue(new AppUser(tenantId, "admin", "hash", Role.ADMIN, null), new Tenant("T", "T"));
    }

    private static MockHttpServletRequest requestWith(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private void run(MockHttpServletRequest request, FilterChain chain) throws ServletException, IOException {
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void validTokenSetsTheTenantAndAuthorityForTheChain() throws Exception {
        AtomicBoolean called = new AtomicBoolean();

        run(requestWith("Bearer " + adminTokenOfTenant(9)), (req, res) -> {
            called.set(true);
            assertEquals(9, TenantContext.require());
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("ROLE_ADMIN", authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).findFirst().orElseThrow());
            assertEquals(9, ((JwtClaims) authentication.getPrincipal()).tenantId());
        });

        assertTrue(called.get());
    }

    @Test
    void tenantIsClearedAfterTheRequest() throws Exception {
        run(requestWith("Bearer " + adminTokenOfTenant(9)), (req, res) -> { });

        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void tenantIsClearedEvenWhenTheChainThrows() {
        assertThrows(ServletException.class, () -> run(requestWith("Bearer " + adminTokenOfTenant(9)), (req, res) -> {
            throw new ServletException("boom");
        }));

        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void aStaleTenantLeftOnThePooledThreadIsClearedByTheNextRequest() throws Exception {
        TenantContext.set(1);

        run(requestWith(null), (req, res) -> { });

        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void tenantIdIsInTheMdcForTheRequestAndClearedInTheSameFinally() throws Exception {
        run(requestWith("Bearer " + adminTokenOfTenant(9)), (req, res) -> assertEquals("9", MDC.get("tenantId")));

        assertNull(MDC.get("tenantId"));
    }

    @Test
    void tenantIdIsClearedFromTheMdcEvenWhenTheChainThrows() {
        assertThrows(ServletException.class, () -> run(requestWith("Bearer " + adminTokenOfTenant(9)), (req, res) -> {
            throw new ServletException("boom");
        }));

        assertNull(MDC.get("tenantId"));
    }

    @Test
    void invalidTokenContinuesAnonymousWithoutAnException() throws Exception {
        AtomicBoolean called = new AtomicBoolean();

        run(requestWith("Bearer not-a-token"), (req, res) -> {
            called.set(true);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertThrows(IllegalStateException.class, TenantContext::require);
        });

        assertTrue(called.get());
    }

    @Test
    void otherAuthorizationSchemesAreIgnored() throws Exception {
        run(requestWith("Basic YWRtaW46YWRtaW4="), (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }

    @Test
    void anEmptyBearerValueIsIgnored() throws Exception {
        run(requestWith("Bearer    "), (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }
}
