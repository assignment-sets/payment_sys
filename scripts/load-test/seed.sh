#!/usr/bin/env bash
set -euo pipefail

# Seed two high-balance accounts for baseline latency / ceiling probes.
# Uses live app on localhost:8080 + dockerized postgres.
# Safe to re-run: dedupes by email.

BASE_URL="${BASE_URL:-http://localhost:8080}"
TARGET_CENTS=1000000
SEED_GRANT_CENTS=1000
TOPUP_CENTS=$((TARGET_CENTS - SEED_GRANT_CENTS))

echo "==> Checking app at $BASE_URL ..."
curl -sf "$BASE_URL/api/users" > /dev/null || { echo "App not reachable at $BASE_URL"; exit 1; }

create_user() {
  local name="$1" email="$2"
  echo "==> Creating user: $name <$email>"
  local resp http_code body
  resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/users" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"email\":\"$email\"}")
  http_code=$(echo "$resp" | tail -n1)
  body=$(echo "$resp" | sed '$d')

  if [ "$http_code" = "201" ] || [ "$http_code" = "200" ]; then
    echo "  created: $body"
  elif [ "$http_code" = "409" ] || echo "$body" | grep -qi "already\|duplicate\|exists"; then
    echo "  already exists (409), will reuse existing user"
  else
    echo "  POST /api/users failed: HTTP $http_code body: $body"
    exit 1
  fi
}

# 1. Ensure users exist
create_user "Bench A" "bench-a@example.com"
create_user "Bench B" "bench-b@example.com"

# 2. Resolve user IDs by email
USERS_JSON=$(curl -sf "$BASE_URL/api/users")
echo "==> Users: $USERS_JSON"

USER_A_ID=$(echo "$USERS_JSON" | jq -r '.[] | select(.email=="bench-a@example.com") | .id')
USER_B_ID=$(echo "$USERS_JSON" | jq -r '.[] | select(.email=="bench-b@example.com") | .id')

if [ -z "$USER_A_ID" ] || [ "$USER_A_ID" = "null" ] || [ -z "$USER_B_ID" ] || [ "$USER_B_ID" = "null" ]; then
  echo "Failed to resolve bench user IDs"
  exit 1
fi
echo "==> Bench A userId=$USER_A_ID  Bench B userId=$USER_B_ID"

# 3. Resolve account IDs and balances
ACCT_A_JSON=$(curl -sf "$BASE_URL/api/accounts/user/$USER_A_ID")
ACCT_B_JSON=$(curl -sf "$BASE_URL/api/accounts/user/$USER_B_ID")
echo "==> Account A: $ACCT_A_JSON"
echo "==> Account B: $ACCT_B_JSON"

ACCT_A_ID=$(echo "$ACCT_A_JSON" | jq -r '.id')
ACCT_B_ID=$(echo "$ACCT_B_JSON" | jq -r '.id')
BAL_A=$(echo "$ACCT_A_JSON" | jq -r '.balance')
BAL_B=$(echo "$ACCT_B_JSON" | jq -r '.balance')

echo "==> Account IDs: A=$ACCT_A_ID (balance=$BAL_A)  B=$ACCT_B_ID (balance=$BAL_B)"

# 4. Top up to TARGET_CENTS if needed (ledger-backed, so drift audit stays clean)
boost_account() {
  local acct_id="$1" current="$2" label="$3"
  if [ "$current" -ge "$TARGET_CENTS" ]; then
    echo "==> $label already at $current >= $TARGET_CENTS, skipping boost"
    return 0
  fi
  local delta=$((TARGET_CENTS - current))
  local txid
  txid=$(uuidgen)
  echo "==> Boosting $label account $acct_id: $current -> $TARGET_CENTS (delta +$delta, tx=$txid)"
  docker exec -i payment-postgres psql -U paymentsys_user -d paymentsys_db -v ON_ERROR_STOP=1 <<SQL
BEGIN;
UPDATE accounts SET balance = $TARGET_CENTS, version = version + 1 WHERE id = $acct_id;
INSERT INTO ledger_entries (transaction_id, account_id, amount, entry_type, created_at)
VALUES ('$txid', $acct_id, $delta, 'ADJUSTMENT', NOW());
COMMIT;
SQL
  echo "  boosted $label"
}

boost_account "$ACCT_A_ID" "$BAL_A" "Bench A"
boost_account "$ACCT_B_ID" "$BAL_B" "Bench B"

# 5. Verify
echo "==> Verifying..."
curl -sf "$BASE_URL/api/accounts/user/$USER_A_ID" | jq .
curl -sf "$BASE_URL/api/accounts/user/$USER_B_ID" | jq .

# 6. Emit env file for latency/ceiling scripts
cat > scripts/load-test/.bench.env <<ENV
BENCH_USER_A_ID=$USER_A_ID
BENCH_USER_B_ID=$USER_B_ID
BENCH_ACCT_A_ID=$ACCT_A_ID
BENCH_ACCT_B_ID=$ACCT_B_ID
BASE_URL=$BASE_URL
ENV
echo "==> Wrote scripts/load-test/.bench.env"
cat scripts/load-test/.bench.env

# Quick drift check (should be 0)
echo "==> Drift check (should be empty)..."
docker exec -i payment-postgres psql -U paymentsys_user -d paymentsys_db -c "
SELECT a.id, a.balance, COALESCE(SUM(l.amount),0) AS ledger_sum, a.balance - COALESCE(SUM(l.amount),0) AS drift
FROM accounts a LEFT JOIN ledger_entries l ON l.account_id = a.id
WHERE a.id IN ($ACCT_A_ID, $ACCT_B_ID)
GROUP BY a.id;
"

echo "==> Seed done. Ready for latency/ceiling probes."
