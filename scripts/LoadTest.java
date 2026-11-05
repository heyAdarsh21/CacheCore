import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free CacheCore load test. Run with Java 21's single-file source
 * launcher:
 *
 * <pre>java scripts/LoadTest.java http://localhost:8080 10 32 1000 10</pre>
 *
 * Arguments: baseUrl, durationSeconds, concurrency, keyCount, missPercent.
 */
public final class LoadTest {

    private static final String VALUE = "benchmark-value";

    public static void main(String[] args) throws Exception {
        String baseUrl = stripTrailingSlash(args.length > 0 ? args[0] : "http://localhost:8080");
        int durationSeconds = intArg(args, 1, 10);
        int concurrency = intArg(args, 2, 32);
        int keyCount = intArg(args, 3, 1000);
        int missPercent = intArg(args, 4, 10);

        if (durationSeconds <= 0 || concurrency <= 0 || keyCount <= 0
                || missPercent < 0 || missPercent > 100) {
            System.err.println("Invalid arguments: duration, concurrency, and keyCount must be positive; "
                    + "missPercent must be 0..100.");
            System.exit(2);
        }

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        System.out.println("CacheCore load test");
        System.out.printf("baseUrl=%s duration=%ds concurrency=%d keys=%d missPercent=%d%n",
                baseUrl, durationSeconds, concurrency, keyCount, missPercent);

        if (!isHealthy(client, baseUrl)) {
            System.err.println("Health check failed. Start CacheCore first: " + baseUrl + "/actuator/health");
            System.exit(1);
        }

        HttpRequest[] hitRequests = new HttpRequest[keyCount];
        HttpRequest[] missRequests = new HttpRequest[keyCount];
        seedKeys(client, baseUrl, keyCount, hitRequests, missRequests);
        warmUp(client, hitRequests);

        AtomicLong hits = new AtomicLong();
        AtomicLong misses = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        List<Latencies> perWorkerLatencies = new ArrayList<>(concurrency);
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        long startNanos = System.nanoTime();
        long endNanos = startNanos + TimeUnit.SECONDS.toNanos(durationSeconds);

        for (int worker = 0; worker < concurrency; worker++) {
            Latencies latencies = new Latencies();
            perWorkerLatencies.add(latencies);
            workers.submit(() -> {
                start.await();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                while (System.nanoTime() < endNanos) {
                    boolean expectMiss = random.nextInt(100) < missPercent;
                    HttpRequest request = expectMiss
                            ? missRequests[random.nextInt(missRequests.length)]
                            : hitRequests[random.nextInt(hitRequests.length)];
                    long begin = System.nanoTime();
                    try {
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        latencies.add(System.nanoTime() - begin);
                        if (response.statusCode() == 200) {
                            hits.incrementAndGet();
                        } else if (response.statusCode() == 404) {
                            misses.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                        }
                    } catch (Exception exception) {
                        errors.incrementAndGet();
                    }
                }
                return null;
            });
        }

        start.countDown();
        workers.shutdown();
        if (!workers.awaitTermination(durationSeconds + 30L, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            System.err.println("Workers did not finish in time");
            System.exit(1);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        long[] allLatencies = merge(perWorkerLatencies);
        long total = hits.get() + misses.get() + errors.get();
        double throughput = elapsedNanos == 0 ? 0.0 : total / (elapsedNanos / 1_000_000_000.0);

        System.out.printf("Requests: %d%n", total);
        System.out.printf("Throughput: %.1f req/s%n", throughput);
        System.out.printf("Hits: %d  Misses: %d  Errors: %d%n", hits.get(), misses.get(), errors.get());
        if (allLatencies.length == 0) {
            System.out.println("Latency: no successful responses recorded");
        } else {
            Arrays.sort(allLatencies);
            System.out.printf("Latency ms: p50=%.3f p95=%.3f p99=%.3f max=%.3f%n",
                    percentileMillis(allLatencies, 50),
                    percentileMillis(allLatencies, 95),
                    percentileMillis(allLatencies, 99),
                    allLatencies[allLatencies.length - 1] / 1_000_000.0);
        }

        if (errors.get() > 0) {
            System.exit(1);
        }
    }

    private static void seedKeys(HttpClient client, String baseUrl, int keyCount,
                                 HttpRequest[] hitRequests, HttpRequest[] missRequests) throws Exception {
        for (int i = 0; i < keyCount; i++) {
            String body = "{\"value\":\"" + VALUE + "\"}";
            HttpRequest put = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/cache/bench-" + i))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = client.send(put, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new IllegalStateException("Seed failed for key " + i + ": HTTP " + response.statusCode());
            }

            hitRequests[i] = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/cache/bench-" + i))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            missRequests[i] = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/cache/missing-" + i))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
        }
    }

    private static void warmUp(HttpClient client, HttpRequest[] hitRequests) throws Exception {
        for (HttpRequest request : hitRequests) {
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    private static boolean isHealthy(HttpClient client, String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    private static long[] merge(List<Latencies> stores) {
        int total = stores.stream().mapToInt(store -> store.size).sum();
        long[] merged = new long[total];
        int offset = 0;
        for (Latencies store : stores) {
            System.arraycopy(store.values, 0, merged, offset, store.size);
            offset += store.size;
        }
        return merged;
    }

    private static double percentileMillis(long[] sorted, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        int index = Math.max(0, Math.min(sorted.length - 1, rank - 1));
        return sorted[index] / 1_000_000.0;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int intArg(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    private static final class Latencies {
        private long[] values = new long[1 << 16];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }
    }
}
