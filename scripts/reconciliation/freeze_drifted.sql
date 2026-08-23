-- =====================================================
-- FREEZE DRIFTED ACCOUNTS (opt-in via run.sh --freeze)
--
-- Freezes ACTIVE accounts whose balance has drifted from
-- their ledger sum. Already-frozen accounts are left alone
-- (idempotent). Un-freezing is a manual ops action:
--   UPDATE accounts SET status='ACTIVE' WHERE id = ...;
--
-- Returns the ids of accounts frozen by this run.
-- =====================================================
UPDATE accounts a
SET status = 'FROZEN'
WHERE a.status = 'ACTIVE'
  AND a.balance <> COALESCE((SELECT SUM(l.amount)
                             FROM ledger_entries l
                             WHERE l.account_id = a.id), 0)
RETURNING id;
