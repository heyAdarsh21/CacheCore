# CacheCore v1.0

CacheCore is a single-node, in-memory cache exposed through an HTTP/JSON API. It
supports string keys and values, optional per-entry TTLs, LRU capacity eviction,
and cumulative hit/miss/eviction/expiration statistics.

> **Not production-ready.** Storage is volatile: every entry is lost when the
> process stops. This release is intentionally single-node and has no
> replication, persistence, authentication, or distributed coordination.

## Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Build and test](#build-and-test)
- [Run](#run)
- [Configuration](#configuration)
- [HTTP API](#http-api)
- [Semantics and validation](#semantics-and-validation)
- [Docker](#docker)
- [Benchmark](#benchmark)
- [Design decisions and limitations](#design-decisions-and-limitations)
- [Project layout](#project-layout)

## Features

- `SET`/replace, `GET`, and `DELETE` for string values.
- Optional TTL in milliseconds using a monotonic clock.
- Positive maximum entry count with least-recently-used (LRU) eviction.
- Lazy expiration on access; no background scheduler.
- Hits, misses, evictions, expirations, current size, maximum size, and hit ratio.
- Thread-safe cache engine with explicit capacity enforcement.
- REST API with consistent status codes and RFC 9457 problem-detail errors.
- Actuator health endpoint at `/actuator/health`.
- Multi-stage Dockerfile and minimal Docker Compose configuration.
- Unit, HTTP, validation, and concurrency tests.

## Architecture

```
HTTP request
    |
    v
web/            CacheController, request/response records, size filter, error handler
    |
    v
engine/         CacheEngine, entry model, stats, validation limits (no Spring dependency)
    ^
    |
config/         CacheProperties, engine bean, startup logging
```

- **`engine/CacheEngine`** owns the store, expiration, eviction, and counters. It
  is a plain Java class and is unit-tested without starting Spring.
- **`config/`** binds `cachecore.max-entries` and creates the singleton engine.
- **`web/`** maps HTTP routes, validates request shape through the engine rules,
  returns JSON, and translates errors into problem details.

### Concurrency strategy

All store and counter mutations run under a single private monitor. A capacity
check, expiration purge, LRU eviction, and insertion therefore happen atomically.
A `LinkedHashMap` in access-order mode stores entries; a successful `GET` or a
replacement moves the key to the most-recently-used position. This is deliberately
simple and correct for one node. The global lock is a throughput ceiling under
heavy contention, not a correctness compromise.

## Prerequisites

- **JDK 21** (`java -version` must report 21).
- No system Maven installation is required: the Maven wrapper is included and
  downloads Maven 3.9.9 on first use.
- Docker and Docker Compose are optional.

On Windows, use `.\mvnw.cmd`; on Linux/macOS use `./mvnw`.

## Build and test

```bash
./mvnw clean verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
```

This runs the complete JUnit 5 suite and builds the executable Spring Boot jar at
`target/cachecore-1.0.0.jar`.

## Run

```bash
java -jar target/cachecore-1.0.0.jar
```

Or during development:

```bash
./mvnw spring-boot:run
```

The service listens on `http://localhost:8080` by default. Check health:

```bash
curl http://localhost:8080/actuator/health
```

## Configuration

| Property / environment variable | Default | Description |
|---|---:|---|
| `server.port` / `SERVER_PORT` | `8080` | HTTP port |
| `cachecore.max-entries` / `CACHECORE_MAX_ENTRIES` | `10000` | Positive maximum live entry count |

Examples:

```bash
SERVER_PORT=9090 CACHECORE_MAX_ENTRIES=500 java -jar target/cachecore-1.0.0.jar
```

```powershell
$env:SERVER_PORT = "9090"; $env:CACHECORE_MAX_ENTRIES = "500"; java -jar target/cachecore-1.0.0.jar
```

An invalid non-positive `max-entries` value fails application startup rather than
silently running with an invalid cache.

## HTTP API

Base path: `/api/v1/cache`.

| Method | Path | Success | Meaning |
|---|---|---|---|
| `PUT` | `/{key}` | `201 Created` for a new live key, `200 OK` for a replacement | Store or replace a value with optional TTL |
| `GET` | `/{key}` | `200 OK` | Return a live value |
| `GET` | `/{key}` (miss) | `404 Not Found` | Cache miss, body code `CACHE_MISS` |
| `DELETE` | `/{key}` | `204 No Content` | Remove a live entry |
| `DELETE` | `/{key}` (miss) | `404 Not Found` | No live entry, body code `CACHE_MISS` |
| `GET` | `/stats` | `200 OK` | Cache statistics snapshot |
| `GET` | `/actuator/health` | `200 OK` | Lightweight health check |

### Put

```bash
curl -i -X PUT http://localhost:8080/api/v1/cache/greeting \
  -H 'Content-Type: application/json' \
  -d '{"value":"hello","ttlMillis":60000}'
```

`201 Created` (a replacement returns `200 OK`):

```json
{
  "key": "greeting",
  "value": "hello",
  "ttlMillis": 60000,
  "created": true
}
```

Omit `ttlMillis` (or send `null`) for an entry that never expires. Replacing an
entry replaces its TTL too, so replacing with no TTL clears the old expiration.

### Get

```bash
curl -i http://localhost:8080/api/v1/cache/greeting
```

```json
{
  "key": "greeting",
  "value": "hello"
}
```

Miss:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No cache entry exists for key 'greeting'",
  "instance": "/api/v1/cache/greeting",
  "code": "CACHE_MISS"
}
```

### Delete

```bash
curl -i -X DELETE http://localhost:8080/api/v1/cache/greeting
```

`204 No Content` when a live entry was removed; `404` with `CACHE_MISS` otherwise.

### Statistics

```bash
curl http://localhost:8080/api/v1/cache/stats
```

```json
{
  "hits": 12,
  "misses": 3,
  "evictions": 1,
  "expirations": 2,
  "size": 42,
  "maxEntries": 10000,
  "hitRatio": 0.8
}
```

Metric semantics:

- **hits:** `GET` requests that returned a live value.
- **misses:** `GET` requests with no live value. A missing key is a miss; an
  expired key is a miss *and* an expiration.
- **evictions:** live entries removed to make room for a new key at capacity.
  Replacing an existing key never increments this counter.
- **expirations:** expired entries detected and removed, whether by `GET`,
  `DELETE`, replacement, at-capacity cleanup, or a statistics snapshot.
- **size:** live (unexpired) entries after expired entries are purged.
- **hitRatio:** `hits / (hits + misses)`, or `0.0` when there have been no reads.

Counters are cumulative since process start. Resetting is not supported.

### Errors

Cache and request errors use `application/problem+json` with a stable `code`.
Framework errors for endpoints outside this contract (for example an unknown
route) also use problem details, but may omit the `code` property.

| Status | Code | Cause |
|---:|---|---|
| `400` | `INVALID_KEY` | Null/blank/too-long/control-character key |
| `400` | `INVALID_VALUE` | Missing value or value longer than 1,048,576 characters |
| `400` | `INVALID_TTL` | TTL is zero, negative, or greater than 365 days |
| `400` | `INVALID_REQUEST` | Missing or malformed JSON body |
| `400` | `RESERVED_KEY` | Attempt to use key `stats` through an entry route |
| `404` | `CACHE_MISS` | No live entry for the key |
| `413` | `REQUEST_TOO_LARGE` | Declared request body exceeds 2 MiB |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |

## Semantics and validation

### Keys and values

- Keys are strings used verbatim, with no trimming.
- Keys must be non-null, non-blank, at most 512 characters, and contain no ISO
  control characters.
- Values must be non-null and at most 1,048,576 characters. Empty values are allowed.
- `stats` is reserved at the HTTP layer so `GET /api/v1/cache/stats` can never be
  mistaken for a lookup of a key named `stats`.

### TTL

- `ttlMillis` is optional, expressed in milliseconds, and must be in `1..31536000000`
  (one year).
- Expiration uses `System.nanoTime()`, which is monotonic.
- An entry is expired at its exact deadline: `now >= expiresAt`.
- Expiration is lazy. Expired entries are removed when observed; there is no
  background cleanup thread.
- Entries without a TTL stay until deleted, evicted, or the process stops.

### Capacity and LRU

- Capacity is enforced by the engine for every insertion.
- A new key at capacity first reclaims expired entries. If the cache is still
  full, exactly one least-recently-used live entry is evicted.
- Successful reads and replacements update recency.
- Replacing an existing key does not evict another entry.
- Expired entries are not treated as valid residents during capacity cleanup.

## Docker

Build and run with Compose:

```bash
docker compose up --build
```

Set `CACHECORE_PORT` to change the host port (the container still listens on
8080), for example `CACHECORE_PORT=9090 docker compose up --build`.

Or build and run directly:

```bash
docker build -t cachecore:1.0.0 .
docker run --rm -p 8080:8080 -e CACHECORE_MAX_ENTRIES=10000 cachecore:1.0.0
```

The image is multi-stage: Maven/JDK 21 builds the jar, and the runtime stage uses
a slim JRE 21 image and runs as a non-root user. The container exposes port 8080
and declares a health check against `/actuator/health`.

> Docker tests are skipped inside the image build; run `./mvnw clean verify`
> separately before building the image.

## Benchmark

A dependency-free load generator is provided at `scripts/LoadTest.java`. It uses
the JDK's `HttpClient`, seeds unique keys, then sends a configurable mix of hits
and misses. It reports throughput and p50/p95/p99/max latency in milliseconds.

Start the service first, then run:

```bash
java scripts/LoadTest.java http://localhost:8080 10 32 1000 10
```

Arguments:

| Argument | Default | Meaning |
|---|---:|---|
| base URL | `http://localhost:8080` | Running CacheCore instance |
| duration seconds | `10` | Measurement duration |
| concurrency | `32` | Concurrent client threads |
| key count | `1000` | Number of pre-seeded hit keys |
| miss percent | `10` | Percentage of reads sent to absent keys |

Output fields:

```
CacheCore load test
baseUrl=... duration=...s concurrency=... keys=... missPercent=...
Requests: <total>
Throughput: <requests per second> req/s
Hits: <hits>  Misses: <misses>  Errors: <errors>
Latency ms: p50=<p50> p95=<p95> p99=<p99> max=<max>
```

Results depend on the machine, JVM warm-up, GC, client overhead, and whether the
service is running in Docker. Run several times and report the distribution; do
not treat a single run as a capacity guarantee.

For an independent tool, `hey` also reports a latency distribution:

```bash
hey -z 15s -c 32 http://localhost:8080/api/v1/cache/bench-1
```

(Seed `bench-1` first with `PUT`; otherwise that command measures miss traffic.)

### Measured in this workspace

These measurements were actually obtained on the development machine
(Windows 11, Temurin 21.0.13) while running the packaged jar locally with the
default capacity, using
`java scripts/LoadTest.java http://localhost:8080 10 32 1000 10`:

```
Run 1: Requests: 106,667  Throughput: 10,664.0 req/s
       Hits: 96,081  Misses: 10,586  Errors: 0
       Latency ms: p50=1.928 p95=7.887 p99=17.405 max=245.765

Run 2: Requests: 111,048  Throughput: 11,101.3 req/s
       Hits: 99,803  Misses: 11,245  Errors: 0
       Latency ms: p50=2.010 p95=7.617 p99=14.072 max=129.850
```

These are two local runs, not production benchmarks. Your hardware and JVM
state will differ.

## Design decisions and limitations

- **Single lock:** straightforward and race-free for this scope; it does not
  scale across cores as well as sharded or lock-free designs.
- **`LinkedHashMap` access order:** provides O(1) recency updates and simple LRU
  selection without a second recency data structure. At-capacity expiration
  cleanup is O(n).
- **Lazy expiration:** no scheduler thread and no background CPU cost. Expired
  entries can sit in memory until they are observed or the cache reaches
  capacity.
- **Monotonic clock:** TTLs are unaffected by wall-clock changes. TTL values are
  not persisted, and deadlines restart after a process restart (all data is lost
  anyway).
- **In-memory only:** there is no disk, database, Redis, replication, persistence,
  clustering, authentication, or TLS termination. Put a reverse proxy in front if
  those are needed.
- **Value size limit:** values are limited by character count, not serialized
  byte size. The declared body-size limit is 2 MiB.
- **No statistics reset:** counters run for the lifetime of the process.
- **Reserved key:** `stats` cannot be stored through the REST API. The engine
  itself does not reserve it, which keeps the engine reusable.
- **Chunked requests:** the 413 filter relies on `Content-Length`; chunked bodies
  without that header are still bounded by value validation and the servlet
  container, but are not rejected at the filter boundary.

## Project layout

```
src/main/java/com/cachecore/
  CacheCoreApplication.java
  config/       CacheProperties, CacheConfiguration
  engine/       CacheEngine, CacheEntry, CacheStats, CacheValidationException, limits
  web/          controller, DTOs, error handler, request-size filter
src/main/resources/application.yml
src/test/java/com/cachecore/
  engine/CacheEngineTest.java
  web/CacheControllerTest.java
scripts/LoadTest.java
Dockerfile
compose.yaml
```
