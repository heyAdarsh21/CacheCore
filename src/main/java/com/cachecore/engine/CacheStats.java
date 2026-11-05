package com.cachecore.engine;

/**
 * Immutable snapshot of cache counters.
 *
 * @param hits          successful reads of a live entry
 * @param misses        reads that found no live entry (missing or expired)
 * @param evictions     entries removed to make room at capacity
 * @param expirations   entries observed and removed because their TTL elapsed
 * @param size          number of live entries after removing expired entries
 * @param maxEntries    configured capacity
 * @param hitRatio      hits / (hits + misses), or 0.0 when there have been no reads
 */
public record CacheStats(
        long hits,
        long misses,
        long evictions,
        long expirations,
        int size,
        int maxEntries,
        double hitRatio
) {
}
