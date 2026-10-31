package com.moveinsync.roster.core.version;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionResolverTest {

    @Test
    void newerVersionIsApplied() {
        assertEquals(VersionDecision.APPLY, VersionResolver.resolve(8, 7));
    }

    @Test
    void equalVersionIsADuplicate() {
        assertEquals(VersionDecision.DUPLICATE, VersionResolver.resolve(7, 7));
    }

    @Test
    void olderVersionIsStale() {
        assertEquals(VersionDecision.STALE, VersionResolver.resolve(6, 7));
    }

    @Test
    void anyRealVersionIsAppliedOverStoredZero() {
        assertEquals(VersionDecision.APPLY, VersionResolver.resolve(1, 0));
    }

    @Test
    void useCaseOneVersionSevenArrivesBeforeVersionSix() {
        assertEquals(VersionDecision.APPLY, VersionResolver.resolve(7, 5));
        assertEquals(VersionDecision.STALE, VersionResolver.resolve(6, 7));
    }

    @Test
    void comparesTheWholeLongRange() {
        assertEquals(VersionDecision.APPLY, VersionResolver.resolve(Long.MAX_VALUE, Long.MAX_VALUE - 1));
    }
}
