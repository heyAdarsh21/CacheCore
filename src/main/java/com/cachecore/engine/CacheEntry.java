package com.cachecore.engine;

/**
 * A stored value and its absolute expiration deadline on the engine's
 * monotonic clock. {@link #NEVER_EXPIRES} marks entries without a TTL.
 */
record CacheEntry(String value, long expiresAtNanos) {

    static final long NEVER_EXPIRES = Long.MAX_VALUE;

    boolean isExpired(long nowNanos) {
        return expiresAtNanos != NEVER_EXPIRES && nowNanos >= expiresAtNanos;
    }
}
