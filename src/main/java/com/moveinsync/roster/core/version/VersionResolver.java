package com.moveinsync.roster.core.version;

public final class VersionResolver {

    private VersionResolver() {
    }

    public static VersionDecision resolve(long incoming, long stored) {
        if (incoming > stored) {
            return VersionDecision.APPLY;
        }
        return incoming == stored ? VersionDecision.DUPLICATE : VersionDecision.STALE;
    }
}
