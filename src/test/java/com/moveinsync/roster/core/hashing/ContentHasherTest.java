package com.moveinsync.roster.core.hashing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContentHasherTest {

    @Test
    void reproducesTheSeedHashOfEmp1001() {
        assertEquals("b6673abe149e162110eedd39d3313cbd043915615e676e4039aa23a9619ec994",
                ContentHasher.hash("Demo Employee One", "emp1001@acme.example", "+919000001001",
                        "EMP-1005", "BLR-WHF", "S1"));
    }

    @Test
    void reproducesTheSeedHashOfEmp1006() {
        assertEquals("ccd2e4ef961ca2c8782bc07a1e59e8fdaf7cb2ae0a2a2beb78c84c8a6f1a038b",
                ContentHasher.hash("Demo Leaver Six", "emp1006@acme.example", "+919000001006",
                        "EMP-1005", "BLR-ECO", "S1"));
    }

    @Test
    void reproducesTheSeedHashOfARowWithANullManager() {
        assertEquals("0a306b9d76f57a802ecfa96894de614e1d124d2dbb890fe3f26dea633dc97fef",
                ContentHasher.hash("Demo Manager Five", "emp1005@acme.example", "+919000001005",
                        null, "BLR-WHF", "S1"));
    }

    @Test
    void aNullFieldContributesTheEmptyString() {
        assertEquals(ContentHasher.hash("a", "", "", "", "", ""), ContentHasher.hash("a", null, null, null, null, null));
    }

    @Test
    void theSameValueInADifferentFieldHashesDifferently() {
        assertNotEquals(ContentHasher.hash("a", null, null, null, null, null),
                ContentHasher.hash(null, "a", null, null, null, null));
    }

    @Test
    void outputIsLowercaseHexOfSha256Length() {
        assertTrue(ContentHasher.hash("Ünï", null, null, null, null, null).matches("[0-9a-f]{64}"));
    }

    @Test
    void anyChangedFieldChangesTheHash() {
        String original = ContentHasher.hash("n", "e", "p", "m", "o", "s");

        assertNotEquals(original, ContentHasher.hash("x", "e", "p", "m", "o", "s"));
        assertNotEquals(original, ContentHasher.hash("n", "x", "p", "m", "o", "s"));
        assertNotEquals(original, ContentHasher.hash("n", "e", "x", "m", "o", "s"));
        assertNotEquals(original, ContentHasher.hash("n", "e", "p", "x", "o", "s"));
        assertNotEquals(original, ContentHasher.hash("n", "e", "p", "m", "x", "s"));
        assertNotEquals(original, ContentHasher.hash("n", "e", "p", "m", "o", "x"));
    }
}
