```
docker run -d \
--name payment-postgres \
-p 5432:5432 \
-e POSTGRES_DB=payments_db \
-e POSTGRES_USER=payments_user \
-e POSTGRES_PASSWORD=payments_pass \
-v payments_pgdata:/var/lib/postgresql/data \
postgres:13-trixie
```
## TODO
- rate limiting
- api auth
- caching user and account data and idempotency gate
- Reconciliation 
- recovery of pending transactions using background queues
- src of money definition
- Zombie Cleanup: Even with this, a server crash during math will leave a record as INITIATED. In a real system, you'd run a background Cron job every 10 mins to find INITIATED records older than 5 mins and either re-process them or mark them FAILED.
- Move stuck "INITIATED" records to "FAILED" after 5 minutes
  UPDATE transfers
  SET status = 'FAILED'
  WHERE status = 'INITIATED'
  AND created_at < NOW() - INTERVAL '5 minutes';

```
  SELECT
  a.id,
  a.balance as current_balance,
  COALESCE(SUM(l.amount), 0) as ledger_sum,
  (a.balance - COALESCE(SUM(l.amount), 0)) as discrepancy
  FROM accounts a
  LEFT JOIN ledger_entries l ON a.id = l.account_id
  GROUP BY a.id, a.balance;
```