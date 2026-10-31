package com.moveinsync.roster.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void requireReturnsTheTenantThatWasSet() {
        TenantContext.set(7);

        assertEquals(7, TenantContext.require());
    }

    @Test
    void requireThrowsWhenNothingIsSet() {
        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void requireThrowsAfterClear() {
        TenantContext.set(7);

        TenantContext.clear();

        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void anotherThreadDoesNotSeeTheTenant() throws InterruptedException {
        TenantContext.set(7);
        Throwable[] seen = new Throwable[1];

        Thread other = new Thread(() -> {
            try {
                TenantContext.require();
            } catch (IllegalStateException expected) {
                seen[0] = expected;
            }
        });
        other.start();
        other.join();

        assertEquals(IllegalStateException.class, seen[0].getClass());
    }
}
