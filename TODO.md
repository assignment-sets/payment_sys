# TODO

## Remaining

### Security
- [ ] **API auth** — protect endpoints (JWT / API key) before any real exposure.

### Quality & Performance
- [ ] **Test coverage** — add unit tests for `TransferProcessor` (state machine, optimistic-lock retry/recovery) and integration tests for concurrent transfers (`TransferServiceTest`, `TransferStreamConsumerTest`, and `AccountCacheServiceTest` are done).

### Frontend
- [ ] **UI async transfer flow (`ui/`)** — update the transfer form/view to handle `202 Accepted` and poll `GET /api/transfers/{transactionId}` until `POSTED` or `FAILED`.

### Later (Only If Needed at Scale)
- [ ] **DB scaling** — `BRIN` index / monthly partitioning on `ledger_entries.created_at`, read/write replica split for account reads.

---

## Completed

- **P0 — Correctness & Money Safety**
  - [x] Fix swallowed exceptions & ensure business failures mark transfers `FAILED`
  - [x] DB-level ledger integrity (`uq_ledger_transaction_account`, `chk_entry_type`, `chk_amount_sign`)
- **P1 — Reliability & Recovery**
  - [x] Zombie cleanup cron (`INITIATED` > 5 mins → `FAILED` / `TIMEOUT`)
  - [x] `ADJUSTMENT` ledger entry type, `accounts.status` (`ACTIVE`/`FROZEN`), initial-balance ledger fix
  - [x] Standalone SQL drift audit (`scripts/reconciliation/run.sh`, `--freeze`)
- **P2 & P3 — Hardening & API Polish**
  - [x] Redis Bucket4j rate limiting, CORS restriction, externalized DB credentials
  - [x] Typed exceptions, `ErrorResponse` DTO, `200`/`202` idempotent replay semantics, query optimization
  - [x] Versioned Redis caching (`payment-redis:6379`) for `User` (`@Cacheable`) & `Account` (Lua optimistic locking + Postgres `LISTEN/NOTIFY` trigger)
- **P4 — Scaling (Tier 0 & Tier 1)**
  - [x] Java 21 virtual threads + Hikari tuning (`maximum-pool-size: 35`)
  - [x] Async `POST /api/transfers` (`202 Accepted`) + `GET /api/transfers/{transactionId}` polling
  - [x] Dedicated Redis Streams (`redis-queue` on `6380`, `transfers:pending`, `transfers:dlq`)
  - [x] Bounded worker pool (`8–12` threads) + Resilience4j `@Bulkhead` (`10`) & `@CircuitBreaker`
