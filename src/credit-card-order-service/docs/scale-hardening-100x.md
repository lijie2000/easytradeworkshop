# 100x Peak Traffic — What Breaks, What This PR Fixes, and Required Infra

Analysis grounded in **Bluebox** production telemetry (verified scope:
`--service credit-card-order-service`).

## Measured baseline (Bluebox)

| Fact | Value |
|---|---|
| Peak observed rate | ~14 req/min |
| Inbound requests / 24h | ~9,330 |
| Downstream calls per inbound request | DB **~0.86**, feature-flag **~0.97**, third-party **~0.002** (~1.83 total) |
| JVM heap | used ~16.5 MB of **~55 MB** limit (~30%) |
| Threads | flat, avg 6.18 / peak 6.23 |
| GC | negligible except one ~441 ms bucket |
| third-party-service | 20 calls/24h; 7-day **p99 ~4.7 s** tail |
| Concurrency metric | **not emitted** by this Java service (monitoring gap) |

**100x peak ≈ 1,400 req/min ≈ ~23 req/s.**

> Note: Bluebox answers from observed telemetry, not forward projections — a
> direct "what breaks at 100x" question returns nothing. The breaking-point
> ordering below is derived from the measured facts above.

## What breaks first at ~23 req/s, in order

| # | Component | Failure mechanism | Fix location |
|---|---|---|---|
| 1 | **DB connections** | `DriverManager.getConnection()` per call = a new TCP+TLS+auth handshake for every one of ~0.86 DB calls/req. At ~23 req/s (~20 conn/s of churn) this saturates DB server connection slots and adds handshake latency to every request. | **Code (this PR)** + infra (DB max connections) |
| 2 | **Request threads via a slow downstream** | `FeatureFlagClient` / `WorkScheduler` used timeout-less `HttpClient`. feature-flag is called ~1:1 per request; if it (or third-party's 4.7 s tail) stalls, request threads block indefinitely → Tomcat pool exhausts → total outage. | **Code (this PR)** |
| 3 | **feature-flag-service hop** | ~0.97 synchronous calls/req with no caching → at 23 req/s that's ~22 downstream calls/s and a hard dependency on flag-service being up and fast. | **Code (this PR)** |
| 4 | **JVM heap / GC** | ~55 MB limit is tight. 100x more concurrent requests + buffers/serialization can push heap and GC pauses up. | **Infra** (heap limit) + code keeps allocations bounded |
| 5 | **Single instance** | One replica caps throughput regardless of code. | **Infra** (replicas + LB + autoscale) |

## Fixed in this PR (code)

1. **DB connection pooling (HikariCP).** `DatabaseHelper.getConnection()` now
   draws from a bounded, reused pool instead of opening a connection per call.
   Same method signature, so no call sites change. Fails fast on pool exhaustion
   (`connectionTimeout`) rather than hanging a request thread. **Also registers
   HikariCP MBeans → pool metrics (active/idle/pending/wait), closing the
   "no pool telemetry" gap Bluebox flagged.**
   Env: `DB_POOL_MAX_SIZE` (30), `DB_POOL_MIN_IDLE` (5),
   `DB_POOL_CONNECTION_TIMEOUT_MS` (3000), plus lifetime/idle knobs.
2. **HTTP client timeouts.** `FeatureFlagClient` and `WorkScheduler` now set
   connect + per-request read timeouts on their `HttpClient`/requests, so a slow
   downstream can no longer pin a thread indefinitely (breaking point #2).
   Env: `FEATURE_FLAG_CONNECT_TIMEOUT_MS` (1000),
   `FEATURE_FLAG_REQUEST_TIMEOUT_MS` (2000),
   `THIRD_PARTY_CONNECT_TIMEOUT_MS` (2000), `THIRD_PARTY_REQUEST_TIMEOUT_MS` (3000).
3. **Feature-flag caching + fallback.** A short-TTL (default 5 s) in-memory cache
   collapses the ~1:1 flag hop to at most one refresh per flag per window
   (breaking point #3), and serves the last known value if a refresh fails — so a
   flag-service blip no longer fails requests. Env: `FEATURE_FLAG_CACHE_TTL_MS`.
4. **Explicit Tomcat thread-pool tuning.** `application.properties` now sets
   `server.tomcat.threads.max`, `min-spare`, `accept-count`, `max-connections`,
   and `connection-timeout` explicitly (all env-overridable) so behaviour under
   load is predictable and a slow request can't occupy a worker forever.
5. *(In-flight)* The **bitcoin gateway client** (PR for the bitcoin feature)
   already uses timeout + retry + circuit breaker and keeps gateway I/O off the
   request path — same discipline applied there.

All changes are **env-tunable with safe defaults** and keep existing behaviour
when the env vars are unset. Build: `./gradlew test` (Java 21) → **BUILD
SUCCESSFUL**, 9 tests, 0 failures.

## Requires infrastructure changes (cannot be fixed in code alone)

These are flagged for the platform/ops owners — code is ready to use them but
can't set them:

- **Raise the container memory / JVM heap limit.** ~55 MB is tight for 100x.
  Increase the pod/container memory limit and `-Xmx` accordingly, then re-baseline
  GC in Bluebox. *(Breaking point #4.)*
- **Horizontal scale + load balancer + autoscaling.** Run ≥N replicas behind the
  LB with HPA/autoscaling on CPU or request rate. The app is stateless on the
  request path; the pool and caches are per-instance and safe to replicate.
  *(Breaking point #5.)*
- **Database server capacity.** Raise MSSQL `max connections` to accommodate
  `DB_POOL_MAX_SIZE × replica_count`, and confirm DB CPU/IO headroom for ~20×
  higher query volume.
- **Add JVM concurrency instrumentation.** This service emits no
  `http.server.active_requests` gauge (only the .NET services do), so concurrency
  can't be observed. Enable the JVM/HTTP-server active-requests metric so pool and
  thread saturation are visible before they bite.
- **Load-test the third-party-service call path specifically** before ramping —
  per Bluebox it's the dependency with the severe tail.

## Verify after deploy (Bluebox)

Once deployed and ramped, re-check in Bluebox: DB call latency and (now-available)
HikariCP pool wait time, feature-flag call volume (should drop sharply from
caching), request-thread saturation, and heap/GC against the raised limit.
