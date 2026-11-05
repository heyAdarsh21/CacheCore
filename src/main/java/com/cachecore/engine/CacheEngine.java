package com.cachecore.engine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe, single-node, in-memory cache with optional per-entry TTL and
 * least-recently-used (LRU) capacity eviction.
 *
 * <p><strong>Concurrency strategy.</strong> Every operation that touches the
 * store or counters is executed while holding one private monitor. The cache is
 * intentionally simple: a single lock makes the capacity check, eviction,
 * expiration check, and insertion one atomic step. This is appropriate for a
 * single-node service and avoids subtle races between those steps. It is a
 * throughput ceiling under contention, not a correctness compromise.</p>
 *
 * <p><strong>Ordering.</strong> A {@link LinkedHashMap} in access order holds
 * live and lazily-expired entries. Successful reads and replacements move a key
 * to the most-recently-used position. The eldest entry is therefore the LRU
 * candidate when a new key must be inserted.</p>
 *
 * <p><strong>Time.</strong> Expiration uses a monotonic nanosecond source (by
 * default {@link System#nanoTime()}). A TTL is an upper bound on how long the
 * entry remains readable; at the exact deadline it is considered expired.
 * Expiration is lazy: an expired entry is removed when it is read, deleted,
 * replaced, purged during an at-capacity insert, or observed by a statistics
 * snapshot.</p>
 *
 * <p>This class has no Spring dependency and can be unit-tested directly.</p>
 */
public final class CacheEngine {

    private final LinkedHashMap<String, CacheEntry> store;
    private final int maxEntries;
    private final LongSupplier nanoTime;
    private final Object lock = new Object();

    private long hits;
    private long misses;
    private long evictions;
    private long expirations;

    /**
     * Creates an engine using {@link System#nanoTime()} as its clock.
     *
     * @param maxEntries positive maximum number of entries
     */
    public CacheEngine(int maxEntries) {
        this(maxEntries, System::nanoTime);
    }

    /**
     * Creates an engine with an injectable monotonic clock. The clock must
     * return nanoseconds and must not go backwards; tests commonly use a
     * mutable fake clock.
     *
     * @param maxEntries positive maximum number of entries
     * @param nanoTime   monotonic nanosecond time source
     */
    public CacheEngine(int maxEntries, LongSupplier nanoTime) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be greater than zero");
        }
        this.maxEntries = maxEntries;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.store = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * Inserts or replaces an entry.
     *
     * <p>Replacing a live entry keeps the current size (no eviction), replaces
     * both value and TTL, and marks the key as most recently used. Passing a
     * {@code null} TTL makes the replacement non-expiring. Replacing an entry
     * that has already expired is treated as an insert and increments the
     * expiration counter.</p>
     *
     * @param key       non-null, non-blank key of at most {@value CacheLimits#MAX_KEY_LENGTH} characters
     * @param value     non-null value of at most {@value CacheLimits#MAX_VALUE_LENGTH} characters
     * @param ttlMillis optional TTL in milliseconds, from 1 to {@value CacheLimits#MAX_TTL_MILLIS}
     * @return {@code true} when no live entry existed for the key before this call
     * @throws CacheValidationException if an argument violates the documented rules
     */
    public boolean put(String key, String value, Long ttlMillis) {
        validateKey(key);
        validateValue(value);
        validateTtl(ttlMillis);

        synchronized (lock) {
            long now = nanoTime.getAsLong();
            CacheEntry current = store.get(key);
            boolean created = current == null;

            if (current != null && current.isExpired(now)) {
                store.remove(key);
                expirations++;
                created = true;
            }

            if (created) {
                // Expired entries are not valid residents: reclaim them before
                // evicting a live entry.
                if (store.size() >= maxEntries) {
                    purgeExpired(now);
                }
                if (store.size() >= maxEntries) {
                    evictLeastRecentlyUsed();
                }
            }

            long expiresAt = ttlMillis == null
                    ? CacheEntry.NEVER_EXPIRES
                    : now + (ttlMillis * 1_000_000L);
            store.put(key, new CacheEntry(value, expiresAt));
            return created;
        }
    }

    /**
     * Returns the live value for a key, or {@code null} for a miss.
     *
     * <p>A missing key increments misses. An expired key increments both misses
     * and expirations and is removed. A hit increments hits and marks the key as
     * most recently used.</p>
     *
     * @param key cache key
     * @return stored value or {@code null}
     * @throws CacheValidationException if the key is invalid
     */
    public String get(String key) {
        validateKey(key);

        synchronized (lock) {
            long now = nanoTime.getAsLong();
            // Access-order lookup moves a present key to the MRU position.
            CacheEntry entry = store.get(key);
            if (entry == null) {
                misses++;
                return null;
            }
            if (entry.isExpired(now)) {
                store.remove(key);
                expirations++;
                misses++;
                return null;
            }
            hits++;
            return entry.value();
        }
    }

    /**
     * Removes a live entry.
     *
     * <p>An expired entry is removed and counted as an expiration, but the
     * result is {@code false} because no live entry existed. A missing key does
     * not affect hit or miss counters.</p>
     *
     * @param key cache key
     * @return {@code true} if a live entry was removed
     * @throws CacheValidationException if the key is invalid
     */
    public boolean delete(String key) {
        validateKey(key);

        synchronized (lock) {
            long now = nanoTime.getAsLong();
            CacheEntry entry = store.get(key);
            if (entry == null) {
                return false;
            }
            if (entry.isExpired(now)) {
                store.remove(key);
                expirations++;
                return false;
            }
            store.remove(key);
            return true;
        }
    }

    /**
     * Returns a consistent statistics snapshot. Expired entries are removed
     * before the current size is reported, so {@code size} never includes
     * entries that would be misses. Statistics are cumulative since process
     * start and are never reset.
     *
     * @return immutable statistics snapshot
     */
    public CacheStats stats() {
        synchronized (lock) {
            purgeExpired(nanoTime.getAsLong());
            long reads = hits + misses;
            double hitRatio = reads == 0 ? 0.0 : (double) hits / reads;
            return new CacheStats(hits, misses, evictions, expirations, store.size(), maxEntries, hitRatio);
        }
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, CacheEntry>> iterator = store.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
                expirations++;
            }
        }
    }

    private void evictLeastRecentlyUsed() {
        Iterator<String> iterator = store.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            evictions++;
        }
    }

    private static void validateKey(String key) {
        if (key == null) {
            throw new CacheValidationException("INVALID_KEY", "key must not be null");
        }
        if (key.isBlank()) {
            throw new CacheValidationException("INVALID_KEY", "key must not be blank");
        }
        if (key.length() > CacheLimits.MAX_KEY_LENGTH) {
            throw new CacheValidationException("INVALID_KEY",
                    "key must be at most " + CacheLimits.MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new CacheValidationException("INVALID_KEY", "key must not contain control characters");
            }
        }
    }

    private static void validateValue(String value) {
        if (value == null) {
            throw new CacheValidationException("INVALID_VALUE", "value must not be null");
        }
        if (value.length() > CacheLimits.MAX_VALUE_LENGTH) {
            throw new CacheValidationException("INVALID_VALUE",
                    "value must be at most " + CacheLimits.MAX_VALUE_LENGTH + " characters");
        }
    }

    private static void validateTtl(Long ttlMillis) {
        if (ttlMillis == null) {
            return;
        }
        if (ttlMillis <= 0) {
            throw new CacheValidationException("INVALID_TTL", "ttlMillis must be greater than zero");
        }
        if (ttlMillis > CacheLimits.MAX_TTL_MILLIS) {
            throw new CacheValidationException("INVALID_TTL",
                    "ttlMillis must be at most " + CacheLimits.MAX_TTL_MILLIS);
        }
    }
}
