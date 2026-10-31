package com.moveinsync.roster.security;

public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(long tenantId) {
        CURRENT.set(tenantId);
    }

    public static long require() {
        Long tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant is set for this thread");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
