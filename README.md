# Payment System

Spring Boot 3.5 (Java 21) · PostgreSQL 13 · Flyway · JPA · Redis 7 · Resilience4j · Bucket4j

A double-entry ledger payment system built to explore money-safety guarantees, security hardening, async worker isolation, and race-safe caching.

---

## Setup

Start PostgreSQL and Redis (`payment-redis` on `6379`, `redis-queue` on `6380`):

```bash
docker compose up -d
```

Run the application:

```bash
set -a; . .env; set +a; ./mvnw spring-boot:run
```

> **Project status & history:** Tracked in [`TODO.md`](./TODO.md).

---

## Architecture Highlights

### 1. Correctness & Auditability
- **Double-entry append-only ledger (`ledger_entries`):** Every transfer writes matching `DEBIT` (`< 0`) and `CREDIT` (`> 0`) rows enforced by DB constraints (`uq_ledger_transaction_account`, `chk_entry_type`, `chk_amount_sign`).
- **Idempotency & Concurrency:** `Idempotency-Key` uniqueness (`202` on creation, `200` on replay) paired with `@Version` optimistic locking and `@Retryable` recovery.
- **Self-healing & Drift Audit:** Scheduled zombie sweep marks stale `INITIATED` transfers (> 5 min) as `FAILED`/`TIMEOUT`; out-of-band reconciliation ([`scripts/reconciliation/run.sh`](./scripts/reconciliation/run.sh)) verifies `accounts.balance` against `SUM(ledger_entries)` (`0.00` drift) and freezes drifted accounts (`FROZEN`) with `ADJUSTMENT` entries.

### 2. Security & Abuse Protection
- **JWT Auth & Ownership:** Stateless Spring Security JWT auth with `USER`/`ADMIN` roles and per-resource ownership checks across accounts, transfers, and reports.
- **Distributed Rate Limiting:** Redis-backed Bucket4j (`@RateLimited`) enforces per-user/IP token buckets across endpoints (`429 Too Many Requests` + `Retry-After`).

### 3. Performance, Isolation & Reporting
- **Async Transfer Queue (`redis-queue:6380`):** `POST /api/transfers` persists `INITIATED`, enqueues to Redis Stream `transfers:pending`, and returns `202 Accepted` + `Location` for polling (`GET /api/transfers/{transactionId}`). Bounded worker threads (`8–12`), Hikari (`35`), and Resilience4j `@Bulkhead(10)` + `@CircuitBreaker` prevent worker bursts from starving HTTP admission; terminal errors route to `transfers:dlq`.
- **Versioned Redis Caching (`payment-redis:6379`):**
  - `User` (`@Cacheable`) and immutable reports use plain TTL caching.
  - Mutable `Account` reads use **Postgres `LISTEN/NOTIFY` (`pg_notify`) + atomic Redis Lua scripts (`SET_IF_NEWER` / tombstone `INVALIDATE_IF_OLDER`) keyed on `accounts.version`** so slow stale reads can never overwrite newer DB commits.
  - Money-moving transfers always read PostgreSQL directly.
- **Two-Path Account Statement Reporting:**
  - **Past windows (`toDate < today UTC`):** Computed once, frozen permanently in `past_account_reports` (`JSONB`), hot-cached in Redis (`3600s` TTL), and served synchronously (`200 OK`).
  - **Current windows (`toDate >= today UTC`):** Queued on `reports:pending` behind an isolated `@Bulkhead(4)` (`202 Accepted`), computed in `@Transactional(readOnly = true)`, and exportable as CSV (`GET /api/reports/{reportId}/download`). Terminal `reportId` results are cached (`300s` TTL); `POST` stays uncached so transfers are reflected immediately.

---

## Benchmarks (Local Single-Node)

> **Note:** Measured on a single local machine running Spring Boot, PostgreSQL, Redis, and `k6` together; treat **relative before/after deltas** (not absolute ceilings) as the architectural signal.

### 1. Async Transfer Queue (`10 → 100` VUs, 1,000 Accounts)

`POST /api/transfers` is never cached; this isolates switching synchronous DB execution to async Redis Streams (`202 Accepted`):

| Metric | Target (SLO) | Sync Baseline | Tuned Async Queue | Delta |
| :--- | :--- | :--- | :--- | :--- |
| **HTTP Success Rate** | $\ge 99.0\%$ | 96.3% (`665` failed) | **100.0%** (`0` failed) | **+3.7% (zero HTTP errors)** |
| **Total Requests** | — | 18,305 | **55,947** | **3.06x volume** |
| **Throughput** | $\ge 100\text{ RPS}$ | 140.8 RPS | **430.35 RPS** | **3.06x throughput** |
| **Median Latency (p50)** | $\le 50\text{ ms}$ | 100.4 ms | **51.6 ms** | **-48.8 ms (-48.6%)** |
| **Tail Latency (p95)** | $\le 250\text{ ms}$ | 571.7 ms (max 1.38s) | **228.6 ms** (max 1.04s) | **-343.1 ms (-60.0%)** |

- **Ledger Reconciliation:** `0.00` balance drift ([`k6-20261116-205036.summary.json`](./scripts/load-test/logs/k6-20261116-205036.summary.json)).

<details>
<summary>Earlier baseline probes & hot-pair contention test (0.00 drift)</summary>

- **Early Sequential Probe (`curl`, $c=1$, pre-auth/pre-async):** `27.9 ms` avg, `26.4 ms` p50, `40.4 ms` p95, `100%` (`100/100`) ([`latency-20260904-092356.log`](./scripts/latency/logs/latency-20260904-092356.log)).
- **Hot-Pair Edge Case (`1 → 2`, 100 VUs):** Intentionally saturated row locks on two accounts; `82.9%` failed with lock timeouts while **zero money was lost or duplicated** (`0.00` ledger drift, stuck rows swept by zombie cleanup) ([`k6-20260904-094055.summary.json`](./scripts/load-test/logs/k6-20260904-094055.summary.json)).

</details>

---

### 2. Redis Caching Effect (`Cache Off` vs. `Cache On`, 30 VUs)

Measured across `User`, `Account`, frozen past report `POST`, and completed `GET /api/reports/{reportId}` (`20s` per scenario, `0.0%` HTTP errors in both runs):

| Endpoint / Overall | Cache Off (`k6-cache-off`) | Cache On (`k6-cache-on`) | Delta |
| :--- | :--- | :--- | :--- |
| **Overall Throughput** (`http_reqs`) | 1,714.1 RPS (`190,053` reqs) | **3,239.0 RPS** (`366,083` reqs) | **1.89x throughput** |
| **Overall Latency** (p50 / p95) | 12.36 ms / 35.91 ms | **6.74 ms / 16.70 ms** | **-45.5% p50 / -53.5% p95** |
| `GET /api/users/{id}` | 178.8 RPS · p50 25.29 ms · p95 62.36 ms | **366.1 RPS** · p50 **12.46 ms** · p95 **29.92 ms** | **2.05x RPS · -52.0% p95** |
| `GET /api/accounts/{id}` | 387.4 RPS · p50 11.62 ms · p95 26.63 ms | **812.4 RPS** · p50 **5.64 ms** · p95 **11.50 ms** | **2.10x RPS · -56.8% p95** |
| `GET /api/accounts/user/{userId}` | 637.3 RPS · p50 7.52 ms · p95 14.56 ms | **787.5 RPS** · p50 **6.20 ms** · p95 **10.14 ms** | **1.24x RPS · -30.4% p95** |
| `POST /api/accounts/{id}/reports` (Past) | 192.8 RPS · p50 24.90 ms · p95 50.91 ms | **550.7 RPS** · p50 **8.15 ms** · p95 **18.29 ms** | **2.86x RPS · -64.1% p95** |
| `GET /api/reports/{reportId}` (Done) | 317.0 RPS · p50 15.86 ms · p95 25.57 ms | **717.9 RPS** · p50 **6.79 ms** · p95 **11.19 ms** | **2.26x RPS · -56.2% p95** |

- **Logs:** [`k6-cache-off-20261121-172320.summary.json`](./scripts/load-test/logs/k6-cache-off-20261121-172320.summary.json) · [`k6-cache-on-20261121-172320.summary.json`](./scripts/load-test/logs/k6-cache-on-20261121-172320.summary.json)
