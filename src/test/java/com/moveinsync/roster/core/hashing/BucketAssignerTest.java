package com.moveinsync.roster.core.hashing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BucketAssignerTest {

    @ParameterizedTest
    @CsvSource({
            "EMP-1001, 101",
            "EMP-1002, 102",
            "EMP-1003, 103",
            "EMP-1004, 104",
            "EMP-1005, 105",
            "EMP-1006, 106",
            "GLX-2001, 57"
    })
    void reproducesTheSeedBuckets(String externalId, int expected) {
        assertEquals(expected, BucketAssigner.bucket(externalId, 256));
    }

    @Test
    void negativeHashCodeStillGivesAValidBucket() {
        assertEquals(Integer.MIN_VALUE, "polygenelubricants".hashCode());
        assertEquals(0, BucketAssigner.bucket("polygenelubricants", 256));
        assertEquals(1, BucketAssigner.bucket("polygenelubricants", 3));
    }

    @Test
    void rejectsANonPositiveBucketCount() {
        assertThrows(IllegalArgumentException.class, () -> BucketAssigner.bucket("EMP-1001", 0));
        assertThrows(IllegalArgumentException.class, () -> BucketAssigner.bucket("EMP-1001", -1));
    }
}
