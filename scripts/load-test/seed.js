#!/usr/bin/env node
// Seed NUM_USERS bench users/accounts with high balance (ledger-backed)
// Usage: NUM_USERS=1000 BASE_URL=http://localhost:8080 node scripts/load-test/seed.js
// Writes: scripts/load-test/.bench.accounts.json (for k6 SharedArray) + .bench.env

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const NUM_USERS = parseInt(process.env.NUM_USERS || '1000', 10);
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const TARGET_CENTS = 1000000;
const SEED_GRANT = 1000;
const CONCURRENCY = 20;
const SCRIPT_DIR = __dirname;
const ACCOUNTS_JSON = path.join(SCRIPT_DIR, '.bench.accounts.json');
const ENV_FILE = path.join(SCRIPT_DIR, '.bench.env');

async function fetchJson(url, opts = {}) {
  const res = await fetch(url, opts);
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body, text };
}

async function createUser(name, email) {
  const { status, body, text } = await fetchJson(`${BASE_URL}/api/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, email }),
  });
  if (status === 201 || status === 200) return body;
  if (status === 409 || (typeof text === 'string' && /already|duplicate|exists/i.test(text))) {
    return null; // exists — will resolve via GET
  }
  throw new Error(`POST /api/users ${email} failed ${status}: ${text}`);
}

async function main() {
  console.log(`==> Seed ${NUM_USERS} bench users at ${BASE_URL} ...`);
  // quick health check
  const health = await fetchJson(`${BASE_URL}/api/users`);
  if (health.status !== 200) throw new Error(`App not reachable at ${BASE_URL}: ${health.status}`);

  // 1. Create users in chunks
  const emails = Array.from({ length: NUM_USERS }, (_, i) => `bench-${String(i+1).padStart(4,'0')}@example.com`);
  let created = 0, existed = 0, failed = 0;
  for (let i = 0; i < emails.length; i += CONCURRENCY) {
    const chunk = emails.slice(i, i+CONCURRENCY);
    const results = await Promise.allSettled(chunk.map((email, idx) => {
      const n = i + idx + 1;
      return createUser(`Bench ${n}`, email).then(r => ({ email, ok: true, created: !!r })).catch(e => ({ email, ok: false, error: e.message }));
    }));
    for (const r of results) {
      if (r.status === 'fulfilled') {
        if (r.value.ok) { if (r.value.created) created++; else existed++; }
        else { failed++; console.error(`  fail ${r.value.email}: ${r.value.error}`); }
      } else { failed++; console.error(`  fail: ${r.reason}`); }
    }
    if ((i/CONCURRENCY) % 10 === 0) console.log(`  chunk ${i/CONCURRENCY+1}/${Math.ceil(emails.length/CONCURRENCY)} — created ${created} existed ${existed}`);
  }
  console.log(`==> Users: created ${created} existed ${existed} failed ${failed}`);

  // 2. Resolve all users -> map email->id
  const allUsersRes = await fetchJson(`${BASE_URL}/api/users`);
  if (allUsersRes.status !== 200) throw new Error(`GET /api/users failed: ${allUsersRes.status}`);
  const allUsers = allUsersRes.body;
  const emailToId = new Map(allUsers.filter(u => u.email.startsWith('bench-')).map(u => [u.email, u.id]));
  const missing = emails.filter(e => !emailToId.has(e));
  if (missing.length) throw new Error(`Missing ${missing.length} bench users after creation, e.g. ${missing.slice(0,3).join(', ')}`);
  console.log(`==> Resolved ${emailToId.size} bench userIds`);

  // 3. Resolve accounts in parallel chunks
  const accounts = [];
  const userEntries = Array.from(emailToId.entries()); // [email, userId]
  for (let i = 0; i < userEntries.length; i += CONCURRENCY) {
    const chunk = userEntries.slice(i, i+CONCURRENCY);
    const results = await Promise.all(chunk.map(async ([email, userId]) => {
      const { status, body } = await fetchJson(`${BASE_URL}/api/accounts/user/${userId}`);
      if (status !== 200) throw new Error(`GET /api/accounts/user/${userId} failed ${status}`);
      return { email, userId, accountId: body.id, balance: body.balance };
    }));
    accounts.push(...results);
    if ((i/CONCURRENCY) % 10 === 0) console.log(`  accounts chunk ${i/CONCURRENCY+1}/${Math.ceil(userEntries.length/CONCURRENCY)}`);
  }
  console.log(`==> Collected ${accounts.length} accounts`);

  // 4. Bulk top-up via single psql transaction (ledger-backed)
  const needBoost = accounts.filter(a => a.balance < TARGET_CENTS);
  console.log(`==> Need boost: ${needBoost.length}/${accounts.length} below ${TARGET_CENTS}`);
  if (needBoost.length > 0) {
    // Build single SQL with many UPDATE+INSERT
    let sql = 'BEGIN;\n';
    for (const a of needBoost) {
      const delta = TARGET_CENTS - a.balance;
      const txid = `${Date.now()}-${a.accountId}-${Math.random().toString(36).slice(2,8)}`;
      // Use uuid-ish via random; psql will cast string to uuid if needed? ledger_entries.transaction_id is uuid, so generate proper uuid via node crypto
      const { randomUUID } = require('crypto');
      const uuid = randomUUID();
      sql += `UPDATE accounts SET balance = ${TARGET_CENTS}, version = version + 1 WHERE id = ${a.accountId};\n`;
      sql += `INSERT INTO ledger_entries (transaction_id, account_id, amount, entry_type, created_at) VALUES ('${uuid}', ${a.accountId}, ${delta}, 'ADJUSTMENT', NOW());\n`;
    }
    sql += 'COMMIT;\n';
    const tmpSql = path.join(SCRIPT_DIR, '.tmp_boost.sql');
    fs.writeFileSync(tmpSql, sql);
    console.log(`  executing ${needBoost.length} boosts via psql...`);
    execSync(`docker exec -i payment-postgres psql -U paymentsys_user -d paymentsys_db -v ON_ERROR_STOP=1 < "${tmpSql}"`, { stdio: 'inherit' });
    fs.unlinkSync(tmpSql);
    // Refresh balances
    for (let i = 0; i < accounts.length; i += CONCURRENCY) {
      const chunk = accounts.slice(i, i+CONCURRENCY);
      await Promise.all(chunk.map(async (a) => {
        const { body } = await fetchJson(`${BASE_URL}/api/accounts/user/${a.userId}`);
        a.balance = body.balance;
      }));
    }
  }

  // 5. Write outputs
  accounts.sort((a,b) => a.accountId - b.accountId);
  fs.writeFileSync(ACCOUNTS_JSON, JSON.stringify(accounts, null, 2));
  console.log(`==> Wrote ${ACCOUNTS_JSON} (${accounts.length} entries)`);

  const envContent = `NUM_USERS=${NUM_USERS}\nBASE_URL=${BASE_URL}\nBENCH_USER_A_ID=${accounts[0]?.userId || 1}\nBENCH_USER_B_ID=${accounts[1]?.userId || 2}\nBENCH_ACCT_A_ID=${accounts[0]?.accountId || 1}\nBENCH_ACCT_B_ID=${accounts[1]?.accountId || 2}\n`;
  fs.writeFileSync(ENV_FILE, envContent);
  console.log(`==> Wrote ${ENV_FILE}\n${envContent}`);

  // 6. Verify via psql
  try {
    const out = execSync(`docker exec -i payment-postgres psql -U paymentsys_user -d paymentsys_db -c "SELECT COUNT(*) FROM accounts; SELECT a.id, a.balance, COALESCE(SUM(l.amount),0) ledger_sum, a.balance-COALESCE(SUM(l.amount),0) drift FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id GROUP BY a.id HAVING a.balance-COALESCE(SUM(l.amount),0)!=0 LIMIT 5;"`, { encoding: 'utf-8' });
    console.log(out);
  } catch (e) { console.error('drift check failed', e.message); }

  console.log(`==> Seed done. Ready for k6 randomized load.`);
}

main().catch(e => { console.error(e); process.exit(1); });
