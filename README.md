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
