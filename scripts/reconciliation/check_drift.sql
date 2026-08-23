-- =====================================================
-- LEDGER DRIFT AUDIT
--
-- Returns one row per account whose balance does not equal
-- the sum of its ledger entries. Clean accounts return
-- nothing. Run via run.sh — do not wire into the app JVM;
-- this check exists to be independent of it.
--
-- Output columns (pipe-separated in -At mode):
--   account_id | status | current_balance | ledger_sum | discrepancy
-- =====================================================
SELECT a.id            AS account_id,
       a.status,
       a.balance       AS current_balance,
       COALESCE(SUM(l.amount), 0) AS ledger_sum,
       a.balance - COALESCE(SUM(l.amount), 0) AS discrepancy
FROM accounts a
LEFT JOIN ledger_entries l ON l.account_id = a.id
GROUP BY a.id, a.status, a.balance
HAVING a.balance <> COALESCE(SUM(l.amount), 0)
ORDER BY ABS(a.balance - COALESCE(SUM(l.amount), 0)) DESC;
