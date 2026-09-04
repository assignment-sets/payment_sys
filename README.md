# Payment System

Spring Boot 3.5 (Java 21) · PostgreSQL · Flyway · JPA · React (Vite)

A double-entry ledger payment system with idempotent transfers, optimistic locking, and retry-based concurrency handling.

## Setup

Start the PostgreSQL database and redis :

```bash
docker compose up --build

```

Then run the app:

```bash
set -a; . .env; set +a; mvn spring-boot:run
```

---

# 🚨 TODO

## 🔴 P0 — Correctness & Money Safety

> Bugs that can lose money or corrupt ledger state. Fix before anything else.

- [x] **Swallowed exception in `TransferService.createTransfer`** — the catch block around `executeMoneyMovement` silently ignores all exceptions ("Errors here are handled by @Recover or logged") but nothing is actually logged. Non-retryable errors (e.g. `InsufficientBalanceException`) never reach `@Recover`, so transfers can stay stuck in PENDING with no trace.
- [x] **Non-retryable failures don't mark transfer FAILED** — `executeMoneyMovement` only recovers from `OptimisticLockingFailureException`; business failures (insufficient balance, invalid state) leave the record in INITIATED/PENDING forever until the zombie cleanup job exists.
- [x] **Ledger integrity constraints** — add a unique constraint on `ledger_entries(transaction_id)` or `(transaction_id, account_id)` to guarantee double-entry completeness per transaction at the DB level.
- [x] **Validate entry type against amount sign** — DEBIT should imply a negative amount; enforce via a CHECK constraint or service-level invariant.

## 🟠 P1 — Reliability & Recovery

> The system must self-heal after crashes and partial failures.

- [x] **Zombie Cleanup** — even with retries, a server crash during math leaves records as INITIATED. Run a background cron every 10 mins to find INITIATED records older than 5 mins and either re-process or mark them FAILED:

  ```sql
  UPDATE transfers
  SET status = 'FAILED'
  WHERE status = 'INITIATED'
    AND created_at < NOW() - INTERVAL '5 minutes';
  ```

- [x] Reconciliation & State Resolution:
  - **1. Ledger Integrity & Drift Audit (Balance Verification):**

    > Implementation order matters: adjustment plumbing first, then schema migration, then the drift source fix — only after all three land do the audit scripts get written against the final shape.
    - **ADJUSTMENT entry type** — drift resolution must go through immutable ledger entries, never silent balance overrides. Requires: extending the DB `chk_entry_type` CHECK to allow `'ADJUSTMENT'`, updating the Java `LedgerEntryType` enum, relaxing `chk_amount_sign` (adjustments may be either sign, unlike DEBIT<0/CREDIT>0), and auditing existing code paths that assume DEBIT/CREDIT are the only values. Resolution procedure: insert an ADJUSTMENT entry for the exact delta + correct the balance within one explicit transaction
    - **New migration for `accounts.status`** — add `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` with `CHECK (status IN ('ACTIVE','FROZEN'))`; status column over boolean for future states (CLOSED etc.). App side: guard in the outbound transfer path that throws a new `AccountFrozenException` (403) — frozen blocks debits only, credits stay allowed
    - **Initial-balance ledger fix** — `AccountService.createAccount` currently seeds `balance = 1000L` ($10 welcome bonus) with no corresponding ledger entry, so every account is born with +1000 drift and would trip the audit instantly. Fix: write an initial CREDIT ledger entry for the seed amount in the same tx as account creation (this also feeds the "source of money" definition below)
    - **Standalone audit scripts** (`scripts/reconciliation/`) — written last, once the schema they audit is final. Independent of the app JVM on purpose: if the app's write logic is buggy, the same JVM auditing itself shares those blind spots. A pure SQL check sees exactly what a DBA sees:
      - `check_drift.sql` — compares `accounts.balance` against `COALESCE(SUM(ledger_entries.amount), 0)` per account; detects drift from manual DB intervention, partial writes, or race conditions
      - `run.sh` — executes the check against the dockerized Postgres, writes findings to a log file inside the folder, exits non-zero on drift. **Log-only by default** — no Slack/PagerDuty/Prometheus, no events pushed anywhere
      - `--freeze` flag — when passed, additionally runs `UPDATE accounts SET status='FROZEN' WHERE <drifted>` to block further outbound transfers on anomalous accounts; un-freezing stays a manual ops action

  - **2. Terminal & Failed Transaction Resolution (DLQ & Recovery Strategy):**
    - **Option A (Manual Audit & DLQ):** Route permanently `FAILED` transfers (after 3x optimistic lock retry exhaustion or sweep from zombie cleanup) to an operational DLQ / Admin Audit dashboard. Inform the client to initiate a new transaction with a fresh idempotency key.

- [x] **Source of money definition** (`src of money`) — formalize where inbound funds originate.

## 🟡 P2 — Security & Hardening

> Must be done before any real exposure.

- [ ] **API auth** — no authentication exists on any endpoint.
- [x] **Rate limiting** — protect transfer endpoints from abuse.
- [x] **Restrict CORS** — `@CrossOrigin` is wide open on both controllers; lock down allowed origins once auth lands.
- [x] **Externalize DB credentials** — currently hardcoded in `application.yaml`; move to env vars / Spring profiles.

## 🟢 P3 — Code Quality & API Polish

> Improvements, not blockers.

- [x] **Typed exceptions** — replace generic `RuntimeException` in `AccountService.getAccountByUserId` with `AccountNotFoundException` (currently falls through to a 500).
- [x] **Better error mapping** — `GlobalExceptionHandler` maps all `PaymentException`s to 400; give AccountNotFound / DuplicateTransfer distinct status codes, and introduce a proper `ErrorResponse` DTO instead of `Map<String, String>`.
- [x] **Idempotent replay semantics** — return `200` (not `201`) when replaying an existing transfer, plus a `Location` header / transfer-id path for polling.
- [ ] **Test coverage** — only the default context-load test exists. Add unit tests for the `TransferProcessor` state machine, idempotency/duplicate handling, optimistic-lock retry path, and integration tests for concurrent transfers.
- [ ] **Pagination** — `GET /api/users` uses an unbounded `findAll`.
- [x] **Query optimization** — split user and account into separate resources: `GET /api/users/{id}` fetches only the user; new `AccountController` serves `GET /api/accounts/user/{userId}` and `GET /api/accounts/{id}`, each resolved in a single query via join-fetch repository methods. Removed `DetailedUserResponse`.
- [ ] **Caching** — cache user/account data and add an idempotency gate at the cache layer.

## 🔵 P4 — Scaling & Throughput

> How to handle a reasonable amount of requests (1k–5k tps on a single DB) without going to FAANG-scale. Tier 0 is config-only and can be done now; Tier 1 decouples admission from execution via a worker queue (deferred).

### Tier 0 — Config-only (no architecture change)

- [x] **Virtual threads (Loom)** — set `spring.threads.virtual.enabled=true` in `application.yaml` (Java 21). Tomcat mounts virtual threads instead of platform threads; fixes thread-per-request blocking on `TransferProcessor.executeMoneyMovement` row locks / retries with ~1 line change.
- [x] **Hikari pool tuning** — in `application.yaml` `spring.datasource.hikari` set `maximum-pool-size: 20`, `minimum-idle: 5`, `connection-timeout` / `idle-timeout`. The money-movement tx holds 2 row locks + 2 ledger inserts — pool exhaustion is the first bottleneck.
- [x] **Do not cache `accounts.balance`** — balance is derived from the immutable `ledger_entries` table; a stale cache reintroduces the drift bug that `scripts/reconciliation/check_drift.sql` detects. Cache `User`/account metadata only (e.g. `TTL 30s` with explicit invalidation on `FROZEN`). Currently no balance caching is implemented — verified no `@Cacheable`/`CacheManager` usage.

### Tier 1 — Async worker queue (admission vs execution)

- [ ] **Make `POST /api/transfers` async (202 Accepted)** — `TransferService.createTransfer` should persist only `INITIATED` via `TransferProcessor.createInitialRecord` and return `202 {transactionId, status=INITIATED}`; client polls `GET /api/transfers/{id}`. The current sync path (`createInitialRecord` + `executeMoneyMovement` in the HTTP thread) is the throughput ceiling.
- [ ] **Choose a queue backend** — keep the `transfers` table as the outbox log and add a worker pool consuming it:
  - **DB as queue (polling)** — `SELECT ... FOR UPDATE SKIP LOCKED WHERE status='INITIATED' LIMIT n` — zero new infra, reuses `TransferRepository` + `TransferZombieCleanup` pattern, polls DB.
  - **Redis Streams (fits current stack)** — `XADD transfers:pending {txId}` + `XREADGROUP` consumer group on the existing `redis:7-alpine` (`docker-compose.yml`). Needs Lettuce stream config + a DLQ stream for permanently `FAILED` (`BUSINESS/TRANSIENT/TIMEOUT`).
  - **RabbitMQ / SQS / Kafka** — if the learning goal is broker semantics (acks, requeue, partition by `fromAccountId` to avoid cross-shard contention). Heavier ops, deferred.
- [ ] **Bounded worker pool** — `@EnableAsync` + `ThreadPoolTaskExecutor` (`core=20 max=50 queueCapacity=500`) independent of Tomcat acceptors; backpressure becomes queue depth, not thread starvation. Retain ordered locking on `(min(from,to))` in `TransferProcessor`.
- [ ] **Backpressure & bulkheads** — keep `RateLimitAspect` (Redis Bucket4j) as admission control; add a circuit-breaker/bulkhead around the worker execution so contention on one hot account doesn't starve others.

### Tier 2 — DB scaling (single primary)

- [ ] **Ledger growth** — `ledger_entries` dominates size; add `BRIN` on `created_at`, retain `uq_ledger_transaction_account` / `chk_entry_type` / `chk_amount_sign` from `V1`/`V2`, consider monthly partitioning. Keep `V4` `idx_transfers_failed_category` for DLQ scans.
- [ ] **Read/write split (later)** — `AccountService` balance/metadata reads can target a replica; all transfers and ledger writes stay on the primary. Not needed until read load dominates.

## Benchmarks & Performance Targets

### 1. Target Objectives (SLOs vs. Actuals)

Performance targets defined for a single-node local environment with 100 concurrent virtual users:

| Metric                   | Target (SLO)         | Baseline (`curl` $c=1$) | Under Load (100 VUs)      | Status                |
| ------------------------ | -------------------- | ----------------------- | ------------------------- | --------------------- |
| **Success Rate**         | $\ge 99.0\%$         | 100%                    | **96.3%**                 | ⚠️ Needs queue        |
| **Failure Rate**         | $\le 1.0\%$          | 0.0%                    | **3.7%** (row contention) | ⚠️ Contention at peak |
| **Throughput**           | $\ge 100\text{ RPS}$ | ~35 RPS (sequential)    | **140.8 RPS**             | ✅ Met target         |
| **Median Latency (p50)** | $\le 50\text{ ms}$   | 26.4 ms                 | **100.4 ms**              | ⚠️ Queue delay        |
| **Tail Latency (p95)**   | $\le 250\text{ ms}$  | 40.4 ms                 | **571.7 ms** (max 1.38s)  | ⚠️ DB lock wait       |

---

### 2. Sequential Baseline Probe (`curl`, $c=1$)

Measures pure execution cost with zero thread or row contention ($N=100$, sequential, rate-limiting disabled):

- **Average Latency:** 27.9 ms
- **Median Latency (p50):** 26.4 ms
- **Tail Latency (p95):** 40.4 ms
- **Min / Max:** 16.9 ms / 56.7 ms
- **Success Rate:** 100% (100/100)
- **Log:** [`scripts/latency/logs/latency-20260904-092356.log`](scripts/latency/logs/latency-20260904-092356.log)

---

### 3. Concurrency Load Test (`k6`, 1,000 Accounts Randomized)

Simulated ramp-up ($10 \rightarrow 20 \rightarrow 50 \rightarrow 100$ VUs over 130s) across a 1,000-account pool to evaluate real-world database contention:

- **Throughput:** 140.8 requests/sec
- **Total Transactions:** 18,305
- **Success Rate:** 96.3% (17,640 succeeded)
- **Failure Rate:** 3.7% (665 failed due to simultaneous row updates)
- **Average Latency:** 186.7 ms
- **Median Latency (p50):** 100.4 ms
- **Tail Latency (p95):** 571.7 ms (Max: 1,385.5 ms)
- **Log:** [`scripts/load-test/logs/k6-20260904-102831.summary.json`](scripts/load-test/logs/k6-20260904-102831.summary.json)

---

### 4. Integrity & Resilience Validation (Hot-Pair Edge Case)

<details>
<summary>View single-account row contention results</summary>

Hammering only two accounts (`1 → 2`) simultaneously with 100 VUs intentionally provoked pessimistic row-lock saturation. While 82.9% of requests failed with timeouts under extreme collision, **zero money was lost or duplicated**:

- **Total balance drift:** 0.00
- **Ledger reconciliation:** 100% matching
- **Stuck transactions:** Cleanly swept into `FAILED` / `TIMEOUT` by scheduled reconciler.
- **Log:** [`scripts/load-test/logs/k6-20260904-094055.summary.json`](scripts/load-test/logs/k6-20260904-094055.summary.json)
</details>
