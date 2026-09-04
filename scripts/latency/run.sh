#!/usr/bin/env bash
set -euo pipefail

# Average latency probe for POST /api/transfers
# Sequential curl (c=1) with unique idempotencyKey per request.
# Result is mean time_total across N requests, logged for proof-of-work.
# Depends on: scripts/load-test/.bench.env (from seed.sh), app at $BASE_URL

BENCH_ENV="scripts/load-test/.bench.env"
if [ ! -f "$BENCH_ENV" ]; then
  echo "Missing $BENCH_ENV — run bash scripts/load-test/seed.sh first"
  exit 1
fi
# shellcheck disable=SC1090
source "$BENCH_ENV"

BASE_URL="${BASE_URL:-http://localhost:8080}"
FROM_ACCT="${BENCH_ACCT_A_ID}"
TO_ACCT="${BENCH_ACCT_B_ID}"
AMOUNT="${AMOUNT:-100}"          # cents per payment (small, keeps balances high for many runs)
N="${N:-100}"                    # sequential requests to average

if ! curl -sf "$BASE_URL/api/users" > /dev/null; then
  echo "App not reachable at $BASE_URL"
  exit 1
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOG_DIR="scripts/latency/logs"
LOG_FILE="$LOG_DIR/latency-$TIMESTAMP.log"
mkdir -p "$LOG_DIR"

echo "==> Latency probe: N=$N c=1  $FROM_ACCT -> $TO_ACCT  amount=$AMOUNT  base=$BASE_URL"
echo "==> Log: $LOG_FILE"
{
  echo "# Latency probe $TIMESTAMP"
  echo "# N=$N c=1 from=$FROM_ACCT to=$TO_ACCT amount=$AMOUNT base=$BASE_URL"
  echo "# commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown) virtual_threads=$(grep -A2 'threads:' src/main/resources/application.yaml | tr '\n' ' ')"
  echo "#"
} | tee "$LOG_FILE"

TMP_TIMES=$(mktemp)
TMP_RAW=$(mktemp)
trap 'rm -f "$TMP_TIMES" "$TMP_RAW"' EXIT

FAILED=0
for i in $(seq 1 "$N"); do
  KEY=$(uuidgen)
  BODY=$(mktemp)
  printf '{"fromAccountId":%s,"toAccountId":%s,"amount":%s,"idempotencyKey":"%s"}' \
    "$FROM_ACCT" "$TO_ACCT" "$AMOUNT" "$KEY" > "$BODY"

  RESP=$(curl -s -w "\n%{http_code} %{time_total}" -X POST "$BASE_URL/api/transfers" \
      -H "Content-Type: application/json" \
      -d @"$BODY" 2>&1)
  HTTP_CODE=$(echo "$RESP" | tail -n1 | awk '{print $1}')
  TIME_TOTAL=$(echo "$RESP" | tail -n1 | awk '{print $2}')
  BODY_RESP=$(echo "$RESP" | sed '$d')

  rm -f "$BODY"

  if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "201" ]; then
    FAILED=$((FAILED+1))
    echo "  [$i/$N] FAIL http=$HTTP_CODE time=${TIME_TOTAL}s key=$KEY body=$BODY_RESP" | tee -a "$LOG_FILE"
  else
    echo "$TIME_TOTAL" >> "$TMP_TIMES"
    echo "  [$i/$N] ok   time=${TIME_TOTAL}s key=$KEY" | tee -a "$LOG_FILE"
    echo "$i $TIME_TOTAL $HTTP_CODE $KEY" >> "$TMP_RAW"
  fi
done

SUCCEEDED=$(wc -l < "$TMP_TIMES" | tr -d ' ')
if [ "$SUCCEEDED" -eq 0 ]; then
  echo "All requests failed ($FAILED/$N)" | tee -a "$LOG_FILE"
  exit 1
fi

MEAN=$(awk '{sum+=$1; n++} END {if(n>0) printf "%.6f", sum/n}' "$TMP_TIMES")
MIN=$(awk 'NR==1{min=$1} {if($1<min)min=$1} END{printf "%.6f", min}' "$TMP_TIMES")
MAX=$(awk 'NR==1{max=$1} {if($1>max)max=$1} END{printf "%.6f", max}' "$TMP_TIMES")
P50=$(sort -n "$TMP_TIMES" | awk -v n="$SUCCEEDED" '{a[NR]=$1} END{idx=int((n*50+50)/100); if(idx<1)idx=1; if(idx>n)idx=n; printf "%.6f", a[idx]}')
P95=$(sort -n "$TMP_TIMES" | awk -v n="$SUCCEEDED" '{a[NR]=$1} END{idx=int((n*95+50)/100); if(idx<1)idx=1; if(idx>n)idx=n; printf "%.6f", a[idx]}')

{
  echo ""
  echo "=== Summary ==="
  echo "succeeded=$SUCCEEDED failed=$FAILED total=$N"
  echo "mean=${MEAN}s  min=${MIN}s  max=${MAX}s  p50=${P50}s  p95=${P95}s"
  echo "log=$LOG_FILE"
  echo "fromAcct=$FROM_ACCT toAcct=$TO_ACCT amount=$AMOUNT"
} | tee -a "$LOG_FILE"

# Append machine-check line for grep
echo "RESULT mean=$MEAN min=$MIN max=$MAX p50=$P50 p95=$P95 succeeded=$SUCCEEDED failed=$FAILED" >> "$LOG_FILE"

echo ""
echo "==> Done. Average latency (mean): ${MEAN}s  (log: $LOG_FILE)"
