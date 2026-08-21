# Payment System

Spring Boot 3.5 (Java 21) · PostgreSQL · Flyway · JPA · React (Vite)

A double-entry ledger payment system with idempotent transfers, optimistic locking, and retry-based concurrency handling.

## Setup

Start the PostgreSQL database:

```bash
docker run -d \
  --name payment-postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=payments_db \
  -e POSTGRES_USER=payments_user \
  -e POSTGRES_PASSWORD=payments_pass \
  -v payments_pgdata:/var/lib/postgresql/data \
  postgres:13-trixie
```

Then run the app:

```bash
./mvnw spring-boot:run
```

---

# 🚨 TODO

## 🔴 P0 — Correctness & Money Safety

> Bugs that can lose money or corrupt ledger state. Fix before anything else.

- [ ] **Swallowed exception in `TransferService.createTransfer`** — the catch block around `executeMoneyMovement` silently ignores all exceptions ("Errors here are handled by @Recover or logged") but nothing is actually logged. Non-retryable errors (e.g. `InsufficientBalanceException`) never reach `@Recover`, so transfers can stay stuck in PENDING with no trace.
- [ ] **Non-retryable failures don't mark transfer FAILED** — `executeMoneyMovement` only recovers from `OptimisticLockingFailureException`; business failures (insufficient balance, invalid state) leave the record in INITIATED/PENDING forever until the zombie cleanup job exists.
- [ ] **Ledger integrity constraints** — add a unique constraint on `ledger_entries(transaction_id)` or `(transaction_id, account_id)` to guarantee double-entry completeness per transaction at the DB level.
- [ ] **Validate entry type against amount sign** — DEBIT should imply a negative amount; enforce via a CHECK constraint or service-level invariant.

## 🟠 P1 — Reliability & Recovery

> The system must self-heal after crashes and partial failures.

- [ ] **Zombie Cleanup** — even with retries, a server crash during math leaves records as INITIATED. Run a background cron every 10 mins to find INITIATED records older than 5 mins and either re-process or mark them FAILED:

  ```sql
  UPDATE transfers
  SET status = 'FAILED'
  WHERE status = 'INITIATED'
    AND created_at < NOW() - INTERVAL '5 minutes';
  ```

- [ ] **Reconciliation** — detect drift between account balances and the append-only ledger:

  ```sql
  SELECT
    a.id,
    a.balance AS current_balance,
    COALESCE(SUM(l.amount), 0) AS ledger_sum,
    (a.balance - COALESCE(SUM(l.amount), 0)) AS discrepancy
  FROM accounts a
  LEFT JOIN ledger_entries l ON a.id = l.account_id
  GROUP BY a.id, a.balance;
  ```

- [ ] **Recovery of pending transactions using background queues**
- [ ] **Source of money definition** (`src of money`) — formalize where inbound funds originate.

## 🟡 P2 — Security & Hardening

> Must be done before any real exposure.

- [ ] **API auth** — no authentication exists on any endpoint.
- [ ] **Rate limiting** — protect transfer endpoints from abuse.
- [ ] **Restrict CORS** — `@CrossOrigin` is wide open on both controllers; lock down allowed origins once auth lands.
- [ ] **Externalize DB credentials** — currently hardcoded in `application.yaml`; move to env vars / Spring profiles.

## 🟢 P3 — Code Quality & API Polish

> Improvements, not blockers.

- [ ] **Typed exceptions** — replace generic `RuntimeException` in `AccountService.getAccountByUserId` with `AccountNotFoundException` (currently falls through to a 500).
- [ ] **Better error mapping** — `GlobalExceptionHandler` maps all `PaymentException`s to 400; give AccountNotFound / DuplicateTransfer distinct status codes, and introduce a proper `ErrorResponse` DTO instead of `Map<String, String>`.
- [ ] **Idempotent replay semantics** — return `200` (not `201`) when replaying an existing transfer, plus a `Location` header / transfer-id path for polling.
- [ ] **Test coverage** — only the default context-load test exists. Add unit tests for the `TransferProcessor` state machine, idempotency/duplicate handling, optimistic-lock retry path, and integration tests for concurrent transfers.
- [ ] **Pagination** — `GET /api/users` uses an unbounded `findAll`.
- [ ] **Query optimization** — `getUserById` issues two separate queries (user + account); consider join fetch or an entity graph.
- [ ] **Caching** — cache user/account data and add an idempotency gate at the cache layer.
