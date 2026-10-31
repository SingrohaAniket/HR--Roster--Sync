package com.moveinsync.roster.core.hashing;

public final class BucketAssigner {

    private BucketAssigner() {
    }

    public static int bucket(String externalId, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        return Math.floorMod(externalId.hashCode(), bucketCount);
    }
}
