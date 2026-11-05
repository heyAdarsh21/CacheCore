package com.cachecore.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheEngineTest {

    private static final long START_NANOS = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong(START_NANOS);

    private CacheEngine engine(int maxEntries) {
        return new CacheEngine(maxEntries, clock::get);
    }

    private void advanceMillis(long millis) {
        clock.addAndGet(millis * 1_000_000L);
    }

    @Test
    void putAndGetRoundTrip() {
        CacheEngine cache = engine(10);

        assertThat(cache.put("alpha", "one", null)).isTrue();
        assertThat(cache.get("alpha")).isEqualTo("one");

        CacheStats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isZero();
        assertThat(stats.size()).isEqualTo(1);
    }

    @Test
    void missingKeyIsAMissAndReturnsNull() {
        CacheEngine cache = engine(10);

        assertThat(cache.get("absent")).isNull();

        CacheStats stats = cache.stats();
        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRatio()).isZero();
        assertThat(stats.size()).isZero();
    }

    @Test
    void replacingAValueKeepsCapacityAndDoesNotEvict() {
        CacheEngine cache = engine(2);
        cache.put("a", "1", null);
        cache.put("b", "2", null);

        assertThat(cache.put("a", "updated", null)).isFalse();

        CacheStats afterUpdate = cache.stats();
        assertThat(afterUpdate.size()).isEqualTo(2);
        assertThat(afterUpdate.evictions()).isZero();
        assertThat(cache.get("a")).isEqualTo("updated");

        cache.put("c", "3", null);
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("updated");
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.stats().evictions()).isEqualTo(1);
    }

    @Test
    void replacingAnEntryUpdatesItsTtl() {
        CacheEngine cache = engine(1);
        cache.put("a", "first", 100L);
        advanceMillis(50);

        assertThat(cache.put("a", "second", 200L)).isFalse();
        advanceMillis(199);
        assertThat(cache.get("a")).isEqualTo("second");

        advanceMillis(1);
        assertThat(cache.get("a")).isNull();
        assertThat(cache.stats().expirations()).isEqualTo(1);
    }

    @Test
    void replacingWithoutTtlClearsExpiration() {
        CacheEngine cache = engine(1);
        cache.put("a", "first", 50L);
        advanceMillis(25);

        assertThat(cache.put("a", "second", null)).isFalse();
        advanceMillis(1_000_000);

        assertThat(cache.get("a")).isEqualTo("second");
    }

    @Test
    void deleteReportsWhetherALiveEntryExisted() {
        CacheEngine cache = engine(10);
        cache.put("a", "1", null);

        assertThat(cache.delete("a")).isTrue();
        assertThat(cache.delete("a")).isFalse();
        assertThat(cache.get("a")).isNull();
        assertThat(cache.stats().misses()).isEqualTo(1);
    }

    @Test
    void expiredEntriesAreNotReturnedAtTheExactDeadline() {
        CacheEngine cache = engine(10);
        cache.put("a", "1", 100L);

        advanceMillis(99);
        assertThat(cache.get("a")).isEqualTo("1");

        advanceMillis(1);
        assertThat(cache.get("a")).isNull();

        CacheStats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.expirations()).isEqualTo(1);
        assertThat(stats.size()).isZero();
    }

    @Test
    void entriesWithoutTtlDoNotExpire() {
        CacheEngine cache = engine(10);
        cache.put("a", "1", null);

        advanceMillis(365L * 24 * 60 * 60 * 1000);

        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.stats().expirations()).isZero();
    }

    @Test
    void deleteOfExpiredEntryReturnsFalseAndCountsExpiration() {
        CacheEngine cache = engine(10);
        cache.put("a", "1", 10L);
        advanceMillis(10);

        assertThat(cache.delete("a")).isFalse();

        CacheStats stats = cache.stats();
        assertThat(stats.expirations()).isEqualTo(1);
        assertThat(stats.size()).isZero();
    }

    @Test
    void successfulReadsUpdateLruOrder() {
        CacheEngine cache = engine(3);
        cache.put("a", "1", null);
        cache.put("b", "2", null);
        cache.put("c", "3", null);

        // a becomes most recently used; b is now the least recently used.
        assertThat(cache.get("a")).isEqualTo("1");
        cache.put("d", "4", null);

        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.stats().evictions()).isEqualTo(1);
    }

    @Test
    void updatesUpdateLruOrder() {
        CacheEngine cache = engine(2);
        cache.put("a", "1", null);
        cache.put("b", "2", null);

        cache.put("a", "updated", null);
        cache.put("c", "3", null);

        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("updated");
        assertThat(cache.get("c")).isEqualTo("3");
    }

    @Test
    void newKeyAtCapacityEvictsExactlyOneLruEntry() {
        CacheEngine cache = engine(2);
        cache.put("a", "1", null);
        cache.put("b", "2", null);

        assertThat(cache.put("c", "3", null)).isTrue();

        CacheStats stats = cache.stats();
        assertThat(stats.size()).isEqualTo(2);
        assertThat(stats.evictions()).isEqualTo(1);
        assertThat(stats.expirations()).isZero();
    }

    @Test
    void expiredEntriesAreReclaimedBeforeLruEviction() {
        CacheEngine cache = engine(2);
        cache.put("expiring", "1", 10L);
        cache.put("live", "2", null);
        advanceMillis(10);

        cache.put("new", "3", null);

        CacheStats stats = cache.stats();
        assertThat(stats.size()).isEqualTo(2);
        assertThat(stats.expirations()).isEqualTo(1);
        assertThat(stats.evictions()).isZero();
        assertThat(cache.get("live")).isEqualTo("2");
        assertThat(cache.get("new")).isEqualTo("3");
        assertThat(cache.get("expiring")).isNull();
    }

    @Test
    void replacingAnExpiredEntryIsTreatedAsAnInsert() {
        CacheEngine cache = engine(1);
        cache.put("a", "old", 10L);
        advanceMillis(10);

        assertThat(cache.put("a", "new", null)).isTrue();

        CacheStats stats = cache.stats();
        assertThat(stats.expirations()).isEqualTo(1);
        assertThat(stats.evictions()).isZero();
        assertThat(stats.size()).isEqualTo(1);
        assertThat(cache.get("a")).isEqualTo("new");
    }

    @Test
    void statisticsExposeCountersAndHitRatio() {
        CacheEngine cache = engine(5);

        cache.get("missing");
        cache.put("a", "1", null);
        cache.get("a");
        cache.get("a");

        CacheStats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRatio()).isCloseTo(2.0 / 3.0, within(1e-12));
        assertThat(stats.size()).isEqualTo(1);
        assertThat(stats.maxEntries()).isEqualTo(5);
        assertThat(stats.evictions()).isZero();
        assertThat(stats.expirations()).isZero();
    }

    @Test
    void statisticsAreZeroAndHitRatioIsSafeWithoutReads() {
        CacheStats stats = engine(7).stats();

        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.hitRatio()).isZero();
        assertThat(stats.size()).isZero();
        assertThat(stats.maxEntries()).isEqualTo(7);
    }

    @Test
    void statisticsPurgeExpiredEntriesExactlyOnce() {
        CacheEngine cache = engine(5);
        cache.put("expiring", "1", 10L);
        cache.put("live", "2", null);
        advanceMillis(10);

        CacheStats first = cache.stats();
        CacheStats second = cache.stats();

        assertThat(first.size()).isEqualTo(1);
        assertThat(first.expirations()).isEqualTo(1);
        assertThat(second.expirations()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(1);
    }

    @Test
    void invalidKeysAreRejected() {
        CacheEngine cache = engine(10);

        assertValidation("INVALID_KEY", () -> cache.put(null, "v", null));
        assertValidation("INVALID_KEY", () -> cache.put("", "v", null));
        assertValidation("INVALID_KEY", () -> cache.put("  \t", "v", null));
        assertValidation("INVALID_KEY", () -> cache.put("a\nb", "v", null));
        assertValidation("INVALID_KEY", () -> cache.put("k".repeat(CacheLimits.MAX_KEY_LENGTH + 1), "v", null));
        assertValidation("INVALID_KEY", () -> cache.get(null));
        assertValidation("INVALID_KEY", () -> cache.delete(" "));
    }

    @Test
    void invalidValuesAreRejectedButEmptyValuesAreAllowed() {
        CacheEngine cache = engine(10);

        assertValidation("INVALID_VALUE", () -> cache.put("a", null, null));
        assertValidation("INVALID_VALUE",
                () -> cache.put("a", "v".repeat(CacheLimits.MAX_VALUE_LENGTH + 1), null));

        cache.put("empty", "", null);
        assertThat(cache.get("empty")).isEmpty();
    }

    @Test
    void invalidTtlsAreRejected() {
        CacheEngine cache = engine(10);

        assertValidation("INVALID_TTL", () -> cache.put("a", "v", 0L));
        assertValidation("INVALID_TTL", () -> cache.put("a", "v", -1L));
        assertValidation("INVALID_TTL", () -> cache.put("a", "v", CacheLimits.MAX_TTL_MILLIS + 1));
    }

    @Test
    void maxEntriesMustBePositive() {
        assertThatThrownBy(() -> new CacheEngine(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheEngine(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentReadersWriterAndDeletersPreserveInvariants() throws Exception {
        int maxEntries = 128;
        int threadCount = 8;
        int iterations = 2_500;
        CacheEngine cache = engine(maxEntries);
        AtomicLong readCount = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < iterations; i++) {
                        int keyNumber = random.nextInt(256);
                        String key = "key-" + keyNumber;
                        String expected = "value-" + keyNumber;
                        int operation = random.nextInt(10);

                        if (operation < 3) {
                            cache.put(key, expected, null);
                        } else if (operation == 3) {
                            cache.delete(key);
                        } else {
                            readCount.incrementAndGet();
                            String actual = cache.get(key);
                            if (actual != null && !expected.equals(actual)) {
                                throw new AssertionError("Read returned a value for the wrong key");
                            }
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        CacheStats stats = cache.stats();
        assertThat(stats.size()).isLessThanOrEqualTo(maxEntries);
        assertThat(stats.maxEntries()).isEqualTo(maxEntries);
        assertThat(stats.hits() + stats.misses()).isEqualTo(readCount.get());
        assertThat(stats.expirations()).isZero();
        assertThat(stats.evictions()).isPositive();

        // Fill the cache with a known ordering, then prove the access-order map
        // still evicts the least recently used entry after the concurrent phase.
        for (int i = 0; i < maxEntries; i++) {
            cache.put("final-" + i, "v", null);
        }
        assertThat(cache.get("final-0")).isEqualTo("v");
        cache.put("final-extra", "v", null);
        assertThat(cache.get("final-1")).isNull();
        assertThat(cache.get("final-0")).isEqualTo("v");
        assertThat(cache.get("final-extra")).isEqualTo("v");
    }

    @Test
    void concurrentUniqueWritersNeverExceedCapacity() throws Exception {
        int maxEntries = 64;
        int threadCount = 8;
        int iterations = 1_000;
        CacheEngine cache = engine(maxEntries);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threadCount; t++) {
                int threadNumber = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        cache.put("t" + threadNumber + "-" + i, "v", null);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        CacheStats stats = cache.stats();
        assertThat(stats.size()).isEqualTo(maxEntries);
        assertThat(stats.evictions()).isEqualTo(threadCount * iterations - maxEntries);
    }

    private static void assertValidation(String expectedCode, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(CacheValidationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
