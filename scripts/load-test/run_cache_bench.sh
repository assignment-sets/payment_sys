#!/usr/bin/env bash
set -euo pipefail

# Benchmarks all Redis-cached endpoints with CACHE_ENABLED=false vs CACHE_ENABLED=true.
# Disables authentication (SECURITY_DISABLED=true) and rate limiting (RATE_LIMIT_ENABLED=false).
# Saves summary.json files under scripts/load-test/logs/ and prints a comparison table.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$SCRIPT_DIR/logs"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

BASE_URL="${BASE_URL:-http://localhost:8080}"
VUS="${VUS:-30}"
DURATION_SEC="${DURATION_SEC:-20}"
SAMPLE_SIZE="${SAMPLE_SIZE:-100}"

mkdir -p "$LOG_DIR"

APP_PID=""
cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    echo "==> Stopping Spring Boot (PID $APP_PID)..."
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
}
trap cleanup EXIT

echo "==> Ensuring Docker Compose dependencies are up..."
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d

for svc in payment-postgres payment-redis payment-redis-queue; do
  echo -n "  waiting for $svc healthy..."
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo starting)" = "healthy" ]; do
    sleep 1
  done
  echo " ok"
done

if [ ! -f "$SCRIPT_DIR/.bench.accounts.json" ]; then
  echo "==> .bench.accounts.json missing; seeding via seed.js..."
  node "$SCRIPT_DIR/seed.js"
fi

# Pre-build once so spring-boot:run starts quickly for both runs
echo "==> Compiling project (skipTests)..."
(cd "$ROOT_DIR" && ./mvnw -q -DskipTests package)

start_app() {
  local cache_enabled="$1"
  local app_log="$LOG_DIR/app-cache-${cache_enabled}-${TIMESTAMP}.log"
  echo "==> Starting Spring Boot (SECURITY_DISABLED=true RATE_LIMIT_ENABLED=false CACHE_ENABLED=$cache_enabled)..."
  (
    cd "$ROOT_DIR"
    exec env \
      SECURITY_DISABLED=true \
      RATE_LIMIT_ENABLED=false \
      CACHE_ENABLED="$cache_enabled" \
      java -jar target/payment-system-0.0.1-SNAPSHOT.jar > "$app_log" 2>&1
  ) &
  APP_PID=$!

  echo -n "  waiting for app readiness at $BASE_URL..."
  for _ in $(seq 1 60); do
    if curl -sf "$BASE_URL/api/users/1" > /dev/null 2>&1; then
      echo " ready (PID $APP_PID)"
      return 0
    fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo " FAILED! Tail of $app_log:"
      tail -n 30 "$app_log"
      exit 1
    fi
    sleep 1
  done
  echo " TIMED OUT! Tail of $app_log:"
  tail -n 30 "$app_log"
  exit 1
}

OFF_SUMMARY="$LOG_DIR/k6-cache-off-${TIMESTAMP}.summary.json"
ON_SUMMARY="$LOG_DIR/k6-cache-on-${TIMESTAMP}.summary.json"

# ---------------------------------------------------------------------------
# Run 1: CACHE_ENABLED=false
# ---------------------------------------------------------------------------
start_app "false"
echo "==> Running k6 cache-OFF baseline (VUs=$VUS, ${DURATION_SEC}s per endpoint)..."
BASE_URL="$BASE_URL" VUS="$VUS" DURATION_SEC="$DURATION_SEC" SAMPLE_SIZE="$SAMPLE_SIZE" WARMUP="false" \
  k6 run --summary-export "$OFF_SUMMARY" "$SCRIPT_DIR/k6-cache.js"
cleanup
sleep 2

# ---------------------------------------------------------------------------
# Run 2: CACHE_ENABLED=true
# ---------------------------------------------------------------------------
echo "==> Flushing payment-redis before cached run..."
docker exec payment-redis redis-cli FLUSHDB > /dev/null
BEFORE_STATS=$(docker exec payment-redis redis-cli INFO stats | grep -E 'keyspace_hits|keyspace_misses' | tr '\r' ' ')

start_app "true"
echo "==> Running k6 cache-ON benchmark (VUs=$VUS, ${DURATION_SEC}s per endpoint, with warmup)..."
BASE_URL="$BASE_URL" VUS="$VUS" DURATION_SEC="$DURATION_SEC" SAMPLE_SIZE="$SAMPLE_SIZE" WARMUP="true" \
  k6 run --summary-export "$ON_SUMMARY" "$SCRIPT_DIR/k6-cache.js"

AFTER_STATS=$(docker exec payment-redis redis-cli INFO stats | grep -E 'keyspace_hits|keyspace_misses' | tr '\r' ' ')
cleanup

echo ""
echo "==> Redis stats before: $BEFORE_STATS"
echo "==> Redis stats after:  $AFTER_STATS"
echo "==> Summaries saved:"
echo "    OFF: $OFF_SUMMARY"
echo "    ON:  $ON_SUMMARY"
echo ""

# ---------------------------------------------------------------------------
# Print comparison table
# ---------------------------------------------------------------------------
node - "$OFF_SUMMARY" "$ON_SUMMARY" "$DURATION_SEC" <<'NODE'
const fs = require('fs');
const [offPath, onPath, durStr] = process.argv.slice(2);
const off = JSON.parse(fs.readFileSync(offPath, 'utf8')).metrics;
const on = JSON.parse(fs.readFileSync(onPath, 'utf8')).metrics;
const dur = Number(durStr);

const endpoints = [
  { label: 'GET /api/users/{id}', trend: 'lat_user_by_id', reqs: 'reqs_user_by_id' },
  { label: 'GET /api/accounts/{id}', trend: 'lat_account_by_id', reqs: 'reqs_account_by_id' },
  { label: 'GET /api/accounts/user/{userId}', trend: 'lat_account_by_user', reqs: 'reqs_account_by_user' },
  { label: 'POST /api/accounts/{id}/reports (past)', trend: 'lat_past_report_post', reqs: 'reqs_past_report_post' },
  { label: 'GET /api/reports/{reportId}', trend: 'lat_report_by_id', reqs: 'reqs_report_by_id' },
];

const fmt = (n) => (n === undefined || n === null ? 'n/a' : Number(n).toFixed(2));

console.log('| Endpoint | Cache OFF avg / p95 (ms) | Cache ON avg / p95 (ms) | p95 Speedup | Cache OFF RPS | Cache ON RPS | RPS Gain |');
console.log('| :--- | ---: | ---: | ---: | ---: | ---: | ---: |');

for (const ep of endpoints) {
  const offT = off[ep.trend]?.values || off[ep.trend] || {};
  const onT = on[ep.trend]?.values || on[ep.trend] || {};
  const offC = (off[ep.reqs]?.values || off[ep.reqs] || {}).count || 0;
  const onC = (on[ep.reqs]?.values || on[ep.reqs] || {}).count || 0;

  const offRps = offC / dur;
  const onRps = onC / dur;
  const p95Speedup = onT['p(95)'] > 0 ? offT['p(95)'] / onT['p(95)'] : 0;
  const rpsGain = offRps > 0 ? onRps / offRps : 0;

  console.log(
    `| ${ep.label} | ${fmt(offT.avg)} / ${fmt(offT['p(95)'])} | ${fmt(onT.avg)} / ${fmt(onT['p(95)'])} | ${fmt(p95Speedup)}x | ${fmt(offRps)} | ${fmt(onRps)} | ${fmt(rpsGain)}x |`
  );
}

const offFail = (off.http_req_failed?.values || off.http_req_failed || {}).rate ?? 0;
const onFail = (on.http_req_failed?.values || on.http_req_failed || {}).rate ?? 0;
console.log(`\nError rate: Cache OFF=${(offFail * 100).toFixed(3)}%, Cache ON=${(onFail * 100).toFixed(3)}%`);
NODE
