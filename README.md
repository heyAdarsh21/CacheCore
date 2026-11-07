# CacheCore

**A small cache server. A closer look at what happens inside one.**

CacheCore is a single-node, in-memory caching service built with Java 21 and Spring Boot. It exposes a REST API for storing, retrieving and deleting key-value entries, with TTL-based expiration, LRU eviction and runtime statistics.

The idea was to build a cache that is simple enough to understand, but substantial enough to explore the engineering decisions behind one: expiration, eviction, concurrency, capacity management and performance.

This is not an attempt to recreate Redis. It is an implementation of a focused caching system, with its internal behaviour kept explicit.

---

## What it does

- **Store and retrieve:** Put, get and delete string key-value entries through an HTTP API.
- **Time-to-live:** Give individual entries an optional lifetime, expressed in milliseconds.
- **LRU eviction:** When capacity is reached, remove the least recently used entry to make room for a new one.
- **Lazy expiration:** Expired entries are removed when encountered; no background cleanup thread is required.
- **Thread safety:** Protect cache operations and internal state using a single synchronization mechanism.
- **Runtime statistics:** Track hits, misses, evictions, expirations, current size and hit ratio.
- **Consistent errors:** Return structured HTTP errors using RFC 9457 Problem Details.
- **Containerized deployment:** Build and run the service using Docker and Docker Compose.

## A look inside

The cache engine is deliberately independent of Spring Boot. It owns the actual storage and cache behaviour; the web layer is responsible for exposing that behaviour over HTTP.

```text
                  Client
                    |
                    | HTTP / JSON
                    v
          +----------------------+
          |    REST API Layer    |
          |                      |
          | CacheController      |
          | Request validation   |
          | Exception handling   |
          +----------------------+
                    |
                    v
          +----------------------+
          |     Cache Engine     |
          |                      |
          | LinkedHashMap        |
          | TTL management       |
          | LRU eviction         |
          | Capacity enforcement |
          | Statistics           |
          +----------------------+
                    ^
                    |
          +----------------------+
          |   Configuration      |
          |                      |
          | CacheProperties      |
          | CacheConfiguration   |
          +----------------------+
```

### The decisions behind the engine

**Storage and eviction**

The engine uses an access-order `LinkedHashMap`. This provides a straightforward way to maintain LRU ordering without introducing a separate recency data structure.

A successful read or replacement updates an entry's recency. When a new entry arrives and the cache is full, the least recently used live entry is evicted.

**Expiration**

TTL is measured using `System.nanoTime()` rather than wall-clock time. A monotonic clock avoids expiration calculations being affected by system clock adjustments.

Expiration is lazy. An expired entry is removed when an operation encounters it or when a capacity/statistics operation performs cleanup. There is no scheduled background task.

**Concurrency**

All store and counter operations are guarded by a single private monitor.

This makes operations such as checking capacity, removing expired entries, selecting an LRU victim and inserting a new entry atomic with respect to other cache operations.

It is a deliberately straightforward design. The trade-off is contention: a single global lock can limit throughput under heavy concurrent workloads. That limitation is part of the design, not something hidden behind a claim of unlimited scalability.

---

## Getting started

### Requirements

- Java 21
- Maven 3.9.9 (not required as a separate installation; the Maven Wrapper is included)
- Docker Desktop and Docker Compose, if running the containerized version

### Build and test

Clone the repository:

```bash
git clone https://github.com/heyAdarsh21/CacheCore.git
cd CacheCore
```

Run the complete test suite:

**Windows PowerShell**

```powershell
.\mvnw.cmd clean verify
```

**Linux / macOS**

```bash
./mvnw clean verify
```

The executable JAR is generated at:

```text
target/cachecore-1.0.0.jar
```

### Run locally

```bash
java -jar target/cachecore-1.0.0.jar
```

By default, the application listens on:

```text
http://localhost:8080
```

Check that it is running:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP"
}
```

---

## Working with the API

All cache operations use the base path:

```text
/api/v1/cache
```

| Method | Endpoint | Behaviour |
|---|---|---|
| PUT | `/{key}` | Create or replace an entry |
| GET | `/{key}` | Retrieve an entry |
| DELETE | `/{key}` | Remove an entry |
| GET | `/stats` | Retrieve cache statistics |
| GET | `/actuator/health` | Check application health |

### 1. Put an entry

```bash
curl -i -X PUT http://localhost:8080/api/v1/cache/greeting \
  -H "Content-Type: application/json" \
  -d '{"value":"hello","ttlMillis":60000}'
```

Response:

```json
{
  "key": "greeting",
  "value": "hello",
  "ttlMillis": 60000,
  "created": true
}
```

A new entry returns `201 Created`. Replacing an existing live entry returns `200 OK`.

The `ttlMillis` field is optional. Omit it or send `null` to create an entry without expiration.

### 2. Get an entry

```bash
curl -i http://localhost:8080/api/v1/cache/greeting
```

Response:

```json
{
  "key": "greeting",
  "value": "hello"
}
```

If the key does not exist or has expired, the API returns `404 Not Found`.

### 3. Delete an entry

```bash
curl -i -X DELETE http://localhost:8080/api/v1/cache/greeting
```

A successful deletion returns `204 No Content`. A missing entry returns `404 Not Found`.

### 4. Inspect cache statistics

```bash
curl http://localhost:8080/api/v1/cache/stats
```

Example response:

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

The counters have the following meanings:

| Metric | Meaning |
|---|---|
| `hits` | GET operations that returned a live value |
| `misses` | GET operations that found no live value |
| `evictions` | Live entries removed to make room for new entries |
| `expirations` | Expired entries detected and removed |
| `size` | Current number of live entries |
| `maxEntries` | Configured capacity |
| `hitRatio` | `hits / (hits + misses)` |

Statistics are cumulative for the lifetime of the application process. There is currently no reset operation.

---

## Configuration

CacheCore exposes its main runtime settings through environment variables.

| Variable | Default | Description |
|---|---:|---|
| `SERVER_PORT` | `8080` | HTTP server port |
| `CACHECORE_MAX_ENTRIES` | `10000` | Maximum number of live cache entries |

For example, on PowerShell:

```powershell
$env:SERVER_PORT = "9090"
$env:CACHECORE_MAX_ENTRIES = "500"

java -jar target/cachecore-1.0.0.jar
```

The maximum entry count must be positive. An invalid value causes application startup to fail.

---

## Running with Docker

CacheCore includes a multi-stage Dockerfile and a Compose configuration.

Build and start:

```bash
docker compose up --build -d
```

Inspect the container:

```bash
docker compose ps
```

View application logs:

```bash
docker compose logs -f
```

Check the health endpoint:

```bash
curl http://localhost:8080/actuator/health
```

Stop the service:

```bash
docker compose down
```

### Change the cache capacity

PowerShell:

```powershell
$env:CACHECORE_MAX_ENTRIES = "500"

docker compose up --build -d
```

Change the host port:

```powershell
$env:CACHECORE_PORT = "9090"

docker compose up -d
```

The application continues to listen on port `8080` inside the container; only the host-side port changes.

### Container design

The Dockerfile uses two stages:

1. A Maven and JDK 21 image to compile and package the application.
2. A Java 21 JRE Alpine image to run the resulting JAR.

The runtime image runs under a dedicated, non-root user and includes a health check against the Actuator endpoint.

The application has no database or external cache dependency. Its entries live in the Java process's memory and disappear when the process stops.

---

## Testing and verification

The automated test suite currently contains **38 tests**.

| Test class | Tests | Coverage |
|---|---:|---|
| `CacheEngineTest` | 23 | Cache operations, TTL, LRU, capacity and concurrency |
| `CacheControllerTest` | 15 | HTTP operations, validation, error handling and health |
| **Total** | **38** | |

The complete suite was executed using:

```powershell
.\mvnw.cmd clean verify
```

**Result: 38 tests, 0 failures, 0 errors, 0 skipped.**

Beyond automated tests, the application was also exercised manually.

Verified behaviours include:

- Creating, retrieving and deleting entries through the REST API.
- Cache misses and structured error responses.
- TTL behaviour before and after expiration.
- LRU eviction under a constrained capacity.
- Concurrent engine operations and capacity invariants.
- Docker image construction and container startup.
- Docker health checks and HTTP requests through the published container port.
- Runtime capacity configuration through Docker Compose.
- LRU eviction inside the running Docker container.

The Docker-specific TTL behaviour has not been independently verified yet.

---

## A small performance experiment

A dependency-free load generator is included at:

```text
scripts/LoadTest.java
```

It uses Java's built-in `HttpClient` and a fixed thread pool to issue concurrent GET requests against a running CacheCore instance.

The workload includes successful reads and misses against absent keys. It reports request throughput and latency percentiles.

Run it with:

```bash
java scripts/LoadTest.java http://localhost:8080 10 32 1000 10
```

Arguments:

| Argument | Value | Meaning |
|---|---:|---|
| Base URL | `http://localhost:8080` | CacheCore instance |
| Duration | `10` | Test duration in seconds |
| Concurrency | `32` | Client worker threads |
| Key count | `1000` | Number of seeded hit keys |
| Miss percentage | `10` | Percentage of reads targeting absent keys |

### Recorded local measurements

The following runs were performed on Windows 11 using Eclipse Temurin 21.0.13, with the packaged JAR running locally and the default cache capacity of 10,000 entries.

| Metric | Run 1 | Run 2 |
|---|---:|---:|
| Requests | 106,667 | 111,048 |
| Throughput | 10,664.0 req/s | 11,101.3 req/s |
| Hits | 96,081 | 99,803 |
| Misses | 10,586 | 11,245 |
| Errors | 0 | 0 |
| p50 latency | 1.928 ms | 2.010 ms |
| p95 latency | 7.887 ms | 7.617 ms |
| p99 latency | 17.405 ms | 14.072 ms |
| Maximum latency | 245.765 ms | 129.850 ms |

These are development-machine measurements, not production capacity claims. Results depend on hardware, JVM warm-up, garbage collection, operating-system scheduling and client overhead.

The load generator primarily exercises concurrent reads. It should not be interpreted as a comprehensive write-contention benchmark.

---

## What this implementation does not try to solve

CacheCore is intentionally limited in scope.

- **Single-node only:** There is no clustering, replication or distributed coordination.
- **Volatile storage:** Entries disappear when the process stops or restarts.
- **No persistence:** There is no disk-backed storage or recovery mechanism.
- **No authentication or TLS:** It is not intended to be exposed directly to untrusted networks.
- **Global synchronization:** A single monitor simplifies correctness but can become a contention bottleneck.
- **Lazy expiration:** Expired entries may remain in memory until an operation encounters them or cleanup is triggered.
- **No statistics reset:** Counters are cumulative since process startup.

There are also practical input limits:

- Keys are limited to 512 characters.
- Values are limited to 1,048,576 characters.
- TTL must be between 1 millisecond and 365 days.
- Declared request bodies larger than 2 MiB are rejected.

This project is best understood as a focused implementation and exploration of in-memory caching mechanics, not as a drop-in replacement for Redis or another production cache platform.

---

## Project structure

```text
CacheCore/
|
|-- src/
|   |-- main/
|   |   |-- java/com/cachecore/
|   |   |   |-- CacheCoreApplication.java
|   |   |   |-- config/
|   |   |   |-- engine/
|   |   |   |-- web/
|   |   |
|   |   |-- resources/
|   |       |-- application.yml
|   |
|   |-- test/
|       |-- java/com/cachecore/
|           |-- engine/
|           |-- web/
|
|-- scripts/
|   |-- LoadTest.java
|
|-- Dockerfile
|-- compose.yaml
|-- pom.xml
|-- mvnw
|-- mvnw.cmd
|-- README.md
```

## Technology

- Java 21
- Spring Boot 3.5.16
- Maven
- JUnit 5
- Docker
- Docker Compose

---
**CacheCore v1.0.0**

Built as an exploration of the engineering behind a simple idea: keeping frequently needed data close, available and fast to retrieve.
