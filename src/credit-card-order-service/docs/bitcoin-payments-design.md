# Bitcoin Payments — Design & Scaling Notes

This feature adds bitcoin payment support to `credit-card-order-service`. The
design is grounded in **production telemetry pulled from Bluebox** (verified
scope: `--service credit-card-order-service`), not assumptions.

## 1. Production baseline (from Bluebox)

| Metric | Value |
|---|---|
| Avg request rate | ~6.3–6.4 req/min |
| Peak observed (1-min) | ~13–14 req/min |
| Traffic mix | ~97% is `GET /v1/orders/{accountId}/status/latest` (polling) |
| Order creation (`POST /v1/orders`) | ~20–160 reqs / window, p95 ~46 ms |
| CPU | 1.9% avg (max 6.3%) |
| Heap | ~26 MB of ~213 MB (~13%) |
| Threads | flat ~6.2 |

**Dependencies** (all 0% error in the window):

| Dependency | Call ratio | p50 / p95 / p99 |
|---|---|---|
| feature-flag-service | ~1:1 per request | 1 / 2 / 4 ms |
| MSSQL database | ~1:1 per request | 1 / 3 / 7 ms |
| third-party-service (payment gateway) | ~0.2% of requests | 5 / 9 ms / **~4,694 ms (7d p99)** |

## 2. Which components will be under the most stress

Ranked by what saturates **first** as load grows:

1. **The external payment gateway call (highest risk).** The existing
   `third-party-service` shows a p99 of ~4.7 s against a ~5 ms median — a rare
   but severe latency tail — and the current HTTP clients
   (`FeatureFlagClient`, `WorkScheduler`) use `java.net.http.HttpClient` with
   **no timeout**. A synchronous, timeout-less external payment call is exactly
   what exhausts request-handling threads under load. A bitcoin gateway has the
   same shape (on-chain settlement is slow and variable), so it is the #1 stress
   point.
2. **The database connection path.** `DatabaseHelper.getConnection()` opens a
   **brand-new JDBC connection per call** via `DriverManager.getConnection(...)`
   — there is **no connection pool**. At 100x volume, per-call connection
   establishment (TCP + TLS + auth handshake to MSSQL) becomes a dominant cost
   and a saturation risk. Bluebox also confirmed there is **no connection-pool
   telemetry**, so this can't currently be measured — a blind spot.
3. **feature-flag-service.** Called ~1:1 per request. Fast today (sub-4 ms p99),
   but at 100x it's a per-request synchronous hop with no caching. Lower risk
   than the above, but worth a short-TTL cache.

Compute (CPU/heap/threads) has enormous headroom and is **not** the constraint.

## 3. Architecture that handles 100x traffic

100x peak ≈ **~1,400 req/min (~23 req/s)** sustained. The design keeps the slow
dependency off the request path and removes the per-call DB connect cost.

```
Client ──POST /v1/bitcoin-payments──▶ BitcoinPaymentController
                                         │ (fast: validate + flag + persist PENDING)
                                         ▼
                                 BitcoinPaymentService ──▶ BitcoinPaymentRepository
                                                                (pooled DB in prod)
        ┌───────────────────────────────────────────────────────────────┐
        │  BitcoinPaymentScheduler (background worker, OFF request path)  │
        │    loads in-flight payments each tick, calls the gateway via    │
        │    BitcoinGatewayClient (timeout + retry + circuit breaker)     │
        └───────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
                              External Bitcoin Gateway
Client ──GET /v1/bitcoin-payments/{id}──▶ poll for status (fast, DB read only)
```

**Key decisions and why:**

- **Async, poll-based flow.** The request thread only validates, checks the flag,
  and persists a `PENDING` payment, then returns `202 Accepted`. All slow gateway
  I/O happens on `BitcoinPaymentScheduler`. This directly neutralizes stress
  point #1 — a gateway tail-latency spike can never exhaust request threads,
  because no request thread ever waits on the gateway. (Mirrors the existing
  `WorkScheduler` pattern the service already uses for `third-party-service`.)
- **Hardened gateway client.** `BitcoinGatewayClient` bounds every call with a
  connect timeout, a per-request read timeout, bounded retries with exponential
  backoff (5xx / network only), and a lightweight **circuit breaker** that fails
  fast when the gateway is unhealthy — so we shed load instead of piling threads
  onto a stalled dependency. All tunable via env with safe defaults.
- **Feature-flag gated.** Rollout is controlled by the
  `bitcoin_payments_enabled` OpenFeature flag (same mechanism as the existing
  flags). Dark-launch, gradual ramp, and instant kill-switch with no redeploy.
- **Idempotent initiation.** A repeated `initiate` for an in-flight order returns
  the existing payment instead of creating a duplicate — safe under client
  retries, which matter at 100x.
- **Repository is the DB seam.** `BitcoinPaymentRepository` is in-memory here to
  keep the change self-contained (no schema migration in the workshop repo), but
  it is the explicit seam where a durable, **pooled** datastore is plugged in.
  For production it MUST be backed by a bounded pool (e.g. HikariCP) with pool
  metrics exported — see §4.

**To actually run at 100x, alongside this code:**
- Back the repository with the real DB and a **HikariCP pool** (bounded, e.g.
  20–50 connections) — removes the per-call connect cost (stress #2).
- Run **≥2 replicas** behind the load balancer; the scheduler is safe to run per
  replica because gateway calls are idempotent per payment. (For strict
  once-only processing across replicas, add a claim/lease column.)
- Add a **short-TTL cache** for feature-flag reads to cut the per-request hop
  (stress #3).
- Keep the gateway timeouts tight so worst-case scheduler tick time stays bounded.

## 4. Existing issues to address alongside this feature

These are pre-existing and surfaced by Bluebox; they matter for this feature's
reliability at scale:

1. **No DB connection pool** (`DatabaseHelper` uses `DriverManager` per call).
   Latent scaling bottleneck for the whole service. Recommend introducing
   HikariCP service-wide.
2. **No connection-pool telemetry.** Bluebox could not measure pool
   utilization/wait time. Add HikariCP metrics so pool saturation is observable
   before it bites.
3. **Timeout-less HTTP clients.** `FeatureFlagClient` and `WorkScheduler` build
   `HttpClient` with no connect/read timeout — the same tail-latency exposure
   this feature guards against. Recommend adding timeouts there too.
4. **Detection gap.** The recent 100%-failure incident on
   `GET /v1/orders/{accountId}/status/latest` (divide-by-zero, now fixed) did
   **not** raise a Davis problem. Worth reviewing alerting so a 100%-failing
   endpoint pages someone.
5. **No SLOs configured.** Bluebox found no SLO objects in this environment, so
   "SLOs at risk" can't be assessed. Define SLOs (availability + latency) for the
   payment endpoints at minimum.

## 5. Observability for this feature

- Endpoints are auto-instrumented by the service's existing tracing.
- State transitions are logged at each step (`PENDING` → `AWAITING_CONFIRMATION`
  → `CONFIRMED`/`FAILED`) with the payment id, so a trace/log correlation is
  possible per payment.
- Circuit-breaker open/close is logged at ERROR/WARN.
- **Before ramping traffic, load-test the gateway call path specifically** — per
  Bluebox, the external-payment tail latency is the first constraint, not CPU or
  memory.

## 6. API

| Method | Path | Behavior |
|---|---|---|
| `POST` | `/v1/bitcoin-payments` | Validate, flag-check, persist `PENDING`, return `202`. Never blocks on the gateway. `503` if the flag is off. |
| `GET` | `/v1/bitcoin-payments/{paymentId}` | Return current status (DB read only). `404` if unknown. |

## 7. Configuration (env, all with safe defaults)

| Var | Default | Meaning |
|---|---|---|
| `BITCOIN_GATEWAY_URL` | `http://bitcoin-gateway:8080` | Gateway base URL |
| `BITCOIN_GATEWAY_CONNECT_TIMEOUT_MS` | `2000` | Connect timeout |
| `BITCOIN_GATEWAY_REQUEST_TIMEOUT_MS` | `3000` | Per-request read timeout |
| `BITCOIN_GATEWAY_MAX_RETRIES` | `2` | Retries for 5xx/network errors |
| `BITCOIN_GATEWAY_CB_FAILURE_THRESHOLD` | `5` | Consecutive failures before opening the circuit |
| `BITCOIN_GATEWAY_CB_OPEN_MS` | `15000` | How long the circuit stays open |
| `BITCOIN_SCHEDULER_DELAY` | `5` | Scheduler start delay (s) |
| `BITCOIN_SCHEDULER_RATE` | `5` | Scheduler tick interval (s) |

Rollout flag: `bitcoin_payments_enabled` (OpenFeature), default **off**.
