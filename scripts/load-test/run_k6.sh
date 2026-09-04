#!/usr/bin/env bash
set -euo pipefail

# Staged k6 ceiling probe — randomized spread among .bench.accounts.json (realistic),
# Re-seeds bench accounts to 1M via seed.js to avoid InsufficientBalance polluting ceiling.
# Saves ONLY summary.json (proof-of-work) under scripts/load-test/logs/.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BENCH_ENV="$SCRIPT_DIR/.bench.env"
LOG_DIR="$SCRIPT_DIR/logs"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$LOG_DIR"

# 1. Ensure bench accounts topped up (seed is idempotent, JS version for 1000)
echo "==> Seeding bench accounts (top-up to 1M) via seed.js..."
if [ -f "$SCRIPT_DIR/seed.js" ]; then
  node "$SCRIPT_DIR/seed.js" 2>&1 | tail -30
else
  bash "$SCRIPT_DIR/seed.sh" 2>&1 | tail -20
fi

# .bench.accounts.json is source of truth for randomized k6
if [ ! -f "$SCRIPT_DIR/.bench.accounts.json" ] && [ ! -f "$BENCH_ENV" ]; then
  echo "Missing $SCRIPT_DIR/.bench.accounts.json and $BENCH_ENV"
  exit 1
fi
if [ -f "$BENCH_ENV" ]; then
  # shellcheck disable=SC1090
  source "$BENCH_ENV"
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"

# Sanity check app reachable
curl -sf "$BASE_URL/api/users" > /dev/null || { echo "App not reachable at $BASE_URL"; exit 1; }

echo "==> Checking sample balances before k6..."
if [ -f "$SCRIPT_DIR/.bench.accounts.json" ]; then
  head -5 "$SCRIPT_DIR/.bench.accounts.json"
  jq length "$SCRIPT_DIR/.bench.accounts.json" | xargs echo "  total bench accounts:"
else
  curl -sf "$BASE_URL/api/accounts/user/$BENCH_USER_A_ID" | jq .
fi

SUMMARY_OUT="$LOG_DIR/k6-${TIMESTAMP}.summary.json"

echo "==> Running k6 staged load (10->20->50->100 VU, ~130s total, randomized)..."
echo "    summary: $SUMMARY_OUT"

# Only summary.json is kept (per user: no 56M json, no stdout.log bloat)
set +e
BASE_URL="$BASE_URL" k6 run --summary-export "$SUMMARY_OUT" "$SCRIPT_DIR/k6-transfer.js" 2>&1 | tail -40
K6_EXIT=$?
set -e

# Quick parse summary if available (summary already written via --summary-export)
if [ -f "$SUMMARY_OUT" ]; then
  echo "==> Summary metrics (from $SUMMARY_OUT):"
  jq -r '.metrics | to_entries[] | "\(.key): count=\(.value.count // .value.values.count // "n/a") avg=\(.value.avg // .value.values.avg // "n/a") p95=\(.value["p(95)"] // .value.values["p(95)"] // "n/a") fails=\(.value.fails // "n/a")"' "$SUMMARY_OUT" 2>&1 | head -40
  echo "--- http_req_duration p95 ---"
  jq '.metrics["http_req_duration"]' "$SUMMARY_OUT" | head -40
  echo "--- http_reqs ---"
  jq '.metrics["http_reqs"]' "$SUMMARY_OUT" | head -20
  echo "--- http_req_failed ---"
  jq '.metrics["http_req_failed"]' "$SUMMARY_OUT" | head -20
fi

# Also show sample balances after (first 2 bench accounts)
echo "==> Sample balances after k6:"
if [ -f "$SCRIPT_DIR/.bench.accounts.json" ]; then
  FIRST_TWO=$(jq -r '.[0:2] | .[].accountId' "$SCRIPT_DIR/.bench.accounts.json" | paste -sd "," -)
  docker exec -i payment-postgres psql -U paymentsys_user -d paymentsys_db -c "
SELECT a.id, a.balance, COALESCE(SUM(l.amount),0) ledger_sum, a.balance-COALESCE(SUM(l.amount),0) drift
FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id
WHERE a.id IN ($FIRST_TWO) GROUP BY a.id;
" 2>&1 | tail -10
fi

echo "==> k6 exit code: $K6_EXIT"
echo "==> Report saved: $SUMMARY_OUT (only summary kept per request)"
# Do not fail the wrapper on threshold breach — we want the report
exit 0
