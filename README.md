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

- [ ] Reconciliation & State Resolution:
  - **1. Ledger Integrity & Drift Audit (Balance Verification):**
    - Periodically execute background reconciliation comparing `accounts.balance` against `COALESCE(SUM(ledger_entries.amount), 0)`.
    - Detect any positive/negative balance drift caused by manual DB intervention, uncommitted partial writes, or race conditions.
    - Alert immediately (Prometheus/Slack/PagerDuty) and freeze anomalous accounts from further outbound transfers.
    - Resolve discrepancies solely via immutable adjustment ledger entries (`ADJUSTMENT_DEBIT` / `ADJUSTMENT_CREDIT`), never silent `UPDATE` overrides.
  - **2. Terminal & Failed Transaction Resolution (DLQ & Recovery Strategy):**
    - **Option A (Manual Audit & DLQ):** Route permanently `FAILED` transfers (after 3x optimistic lock retry exhaustion or sweep from zombie cleanup) to an operational DLQ / Admin Audit dashboard. Inform the client to initiate a new transaction with a fresh idempotency key.
    - **Option B (Automated Recovery Replay):** For transfers marked `FAILED` due to transient system outages/database contention rather than permanent business rule rejections (e.g., `InsufficientBalanceException`), run a delayed reconciliation worker to re-evaluate and safely re-process.

- [ ] **Recovery of pending transactions using background queues**
- [ ] **Source of money definition** (`src of money`) — formalize where inbound funds originate.

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
