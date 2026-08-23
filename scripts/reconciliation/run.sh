#!/usr/bin/env bash
# =====================================================
# LEDGER RECONCILIATION RUNNER
#
# Standalone drift audit — deliberately outside the app JVM
# so the auditor never shares blind spots with the writer.
#
# Usage:
#   ./run.sh            # log-only: report drifted accounts
#   ./run.sh --freeze   # also freeze ACTIVE drifted accounts
#
# Exit codes:
#   0 — no drift
#   1 — operational error (container down, SQL failure)
#   2 — drift detected (with or without freezing)
#
# Config: sources ../../.env when present; falls back to the
# docker-compose defaults. Override container via PG_CONTAINER.
# No alerts are pushed anywhere by design — findings go to the
# timestamped log file in logs/ and to stdout.
# =====================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck disable=SC1091
[ -f "$PROJECT_ROOT/.env" ] && source "$PROJECT_ROOT/.env"

PG_CONTAINER="${PG_CONTAINER:-payment-postgres}"
DB_USERNAME="${DB_USERNAME:-paymentsys_user}"
DB_NAME="$(echo "${DB_URL:-jdbc:postgresql://localhost:5433/paymentsys_db}" | sed -E 's|.*/||; s|\?.*||')"
FREEZE=false
[ "${1:-}" = "--freeze" ] && FREEZE=true

LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/drift-$TIMESTAMP.log"

log() { echo "$@" | tee -a "$LOG_FILE"; }

psql_exec() {
    # SQL is piped via stdin (-i): psql -f would resolve the path inside
    # the container, but the scripts live on the host.
    local sql_file="$1"
    docker exec -i "$PG_CONTAINER" psql -U "$DB_USERNAME" -d "$DB_NAME" \
        -v ON_ERROR_STOP=1 -At -F'|' < "$sql_file"
}

log "=== Ledger reconciliation run @ $(date -Iseconds) ==="
log "container=$PG_CONTAINER db=$DB_NAME freeze=$FREEZE"

if ! docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
    log "ERROR: container '$PG_CONTAINER' is not running. Start it with 'docker compose up -d'."
    exit 1
fi

DRIFT_ROWS="$(psql_exec "$SCRIPT_DIR/check_drift.sql")" || {
    log "ERROR: drift check failed (see above)."
    exit 1
}

DRIFT_COUNT="$(echo "$DRIFT_ROWS" | grep -c . || true)"

if [ "$DRIFT_COUNT" -eq 0 ]; then
    log "OK: all account balances match their ledger sums."
    exit 0
fi

log "DRIFT DETECTED: $DRIFT_COUNT account(s) inconsistent."
log "account_id|status|current_balance|ledger_sum|discrepancy"
echo "$DRIFT_ROWS" | tee -a "$LOG_FILE"

if [ "$FREEZE" = true ]; then
    # Only a psql/docker failure counts as an error here; grep finding no
    # ids simply means every drifted account was already frozen.
    if ! FREEZE_RAW="$(psql_exec "$SCRIPT_DIR/freeze_drifted.sql")"; then
        log "ERROR: freeze step failed after drift was detected. Accounts NOT frozen — investigate manually."
        exit 1
    fi
    # Strip psql's "UPDATE n" command tag — keep only returned ids.
    FROZEN_IDS="$(echo "$FREEZE_RAW" | grep -E '^[0-9]+$' || true)"
    if [ -n "$FROZEN_IDS" ]; then
        log "FROZEN: $(echo "$FROZEN_IDS" | tr '\n' ' ')"
    else
        log "FROZEN: none (drifted accounts were already frozen)."
    fi
fi

exit 2
