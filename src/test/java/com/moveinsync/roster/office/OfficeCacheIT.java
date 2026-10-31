package com.moveinsync.roster.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.benmanes.caffeine.cache.Cache;
import com.moveinsync.roster.config.CacheConfig;
import com.moveinsync.roster.domain.Office;
import com.moveinsync.roster.ingest.AbstractIngestIT;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class OfficeCacheIT extends AbstractIngestIT {

    @MockitoSpyBean
    private OfficeRepository repository;

    @Autowired
    private OfficeDirectory offices;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void startCold() {
        cacheManager.getCache(CacheConfig.OFFICES).clear();
        clearInvocations(repository);
    }

    private void verifyDatabaseReads(int times) {
        verify(repository, times(times)).findByTenantIdAndCodeAndActiveTrue(any(), any());
    }

    @Test
    void aSecondLookupOfTheSameOfficeDoesNotHitTheDatabase() {
        Office first = offices.findActive(ACME, "BLR-WHF").orElseThrow();
        Office second = offices.findActive(ACME, "BLR-WHF").orElseThrow();

        assertEquals(first, second);
        assertEquals("BLR-WHF", second.getCode());
        verifyDatabaseReads(1);
    }

    @Test
    void writingAnOfficeEvictsItSoTheNextLookupSeesTheChange() {
        jdbc.update("INSERT IGNORE INTO office (tenant_id, code, name, city) VALUES (1, 'EVICT-OFC', 'Evict', 'X')");
        Office office = offices.findActive(ACME, "EVICT-OFC").orElseThrow();
        verifyDatabaseReads(1);

        try {
            office.setActive(false);
            offices.save(office);

            assertTrue(offices.findActive(ACME, "EVICT-OFC").isEmpty(), "the deactivated office must not be served from the cache");
            verifyDatabaseReads(2);
        } finally {
            jdbc.update("DELETE FROM office WHERE tenant_id = 1 AND code = 'EVICT-OFC'");
        }
    }

    @Test
    void writingOneOfficeLeavesOtherEntriesCached() {
        Office whitefield = offices.findActive(ACME, "BLR-WHF").orElseThrow();
        offices.findActive(ACME, "BLR-ECO").orElseThrow();
        clearInvocations(repository);

        offices.save(whitefield);
        offices.findActive(ACME, "BLR-ECO").orElseThrow();

        verifyDatabaseReads(0);
    }

    @Test
    void theSameCodeInTwoTenantsIsTwoEntries() {
        jdbc.update("INSERT IGNORE INTO office (tenant_id, code, name, city) VALUES (2, 'BLR-WHF', 'Globex Copy', 'X')");
        try {
            Office acme = offices.findActive(ACME, "BLR-WHF").orElseThrow();
            Office globex = offices.findActive(GLOBEX, "BLR-WHF").orElseThrow();

            assertEquals(ACME, acme.getTenantId());
            assertEquals(GLOBEX, globex.getTenantId());
            verifyDatabaseReads(2);
        } finally {
            jdbc.update("DELETE FROM office WHERE tenant_id = 2 AND code = 'BLR-WHF'");
        }
    }

    @Test
    void aMissIsCachedSoABadCodeRepeatedThroughABatchReadsTheDatabaseOnce() throws Exception {
        var response = send(operator(),
                hr(unique("MISS"), "UPDATE", 1, Map.of("officeCode", "NOPE-CODE")),
                hr(unique("MISS"), "UPDATE", 1, Map.of("officeCode", "NOPE-CODE")),
                hr(unique("MISS"), "UPDATE", 1, Map.of("officeCode", "NOPE-CODE")));

        assertEquals(3, body(response).get("rejected").asInt());
        verifyDatabaseReads(1);
    }

    @Test
    void aStaleMissIsAVisibleRejectionTheClientCanRetryNeverASilentBadWrite() throws Exception {
        String id = unique("STALE");
        String eventId = unique("evt");
        String token = operator();
        Map<String, Object> event = event(eventId, id, "HR", "UPDATE", 2, Map.of("officeCode", "LATE-OFC"));

        var firstAttempt = body(send(token, event)).get("results").get(0);
        assertEquals("REJECTED", firstAttempt.get("outcome").asText());
        assertEquals("UNKNOWN_OFFICE", firstAttempt.get("errors").get(0).get("code").asText());
        assertEquals(0, employeeCount(ACME, id), "nothing was written");

        jdbc.update("INSERT IGNORE INTO office (tenant_id, code, name, city) VALUES (1, 'LATE-OFC', 'Late', 'X')");
        try {
            assertEquals("REJECTED", outcome(send(token, event), 0), "stale, and it says so");
            assertEquals(0, employeeCount(ACME, id));

            cacheManager.getCache(CacheConfig.OFFICES).clear();

            assertEquals("APPLIED", outcome(send(token, event), 0), "the same event, and the key was never claimed");
            assertEquals(1, employeeCount(ACME, id));
        } finally {
            jdbc.update("DELETE FROM outbox WHERE aggregate_id = ?", id);
            jdbc.update("DELETE FROM roster_event WHERE employee_external_id = ?", id);
            jdbc.update("DELETE FROM employee WHERE external_id = ?", id);
            jdbc.update("DELETE FROM office WHERE tenant_id = 1 AND code = 'LATE-OFC'");
        }
    }

    @Test
    void theTtlAndSizeComeFromRosterCache() {
        @SuppressWarnings("unchecked")
        Cache<Object, Object> cache = (Cache<Object, Object>) ((CaffeineCache) cacheManager.getCache(CacheConfig.OFFICES)).getNativeCache();

        assertEquals(Duration.ofMinutes(10), cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
        assertEquals(10_000, cache.policy().eviction().orElseThrow().getMaximum());
        assertFalse(cache.policy().expireAfterAccess().isPresent(), "after access, a code read on every record would never expire");
    }
}
