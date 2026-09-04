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

> **Priorities & history:** Tracked in [`TODO.md`](./TODO.md).

---

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
