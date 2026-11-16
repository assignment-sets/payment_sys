import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

// Realistic spread: random pair among 1000 bench accounts (see seed.js -> .bench.accounts.json)
// Falls back to hot-pair 1->2 if file missing (e.g. quick smoke before seed).
// Rate limiter disabled via --rate-limit.enabled=false for baseline.

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '30s', target: 20 },
    { duration: '30s', target: 50 },
    { duration: '30s', target: 100 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate < 0.01'],
    http_req_duration: ['p(95)<100'],
  },
  noConnectionReuse: false,
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AMOUNT = 100;

let accounts = [];
try {
  accounts = new SharedArray('bench', () => JSON.parse(open('./.bench.accounts.json')));
} catch (e) {
  // fallback: hot pair
  accounts = [{ accountId: 1 }, { accountId: 2 }];
}

export default function () {
  let fromId, toId;
  if (accounts.length >= 2) {
    const fromIdx = Math.floor(Math.random() * accounts.length);
    let toIdx = Math.floor(Math.random() * (accounts.length - 1));
    if (toIdx >= fromIdx) toIdx += 1;
    fromId = accounts[fromIdx].accountId;
    toId = accounts[toIdx].accountId;
  } else {
    fromId = 1; toId = 2;
  }

  const key = `${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
  const payload = JSON.stringify({
    fromAccountId: Number(fromId),
    toAccountId: Number(toId),
    amount: AMOUNT,
    idempotencyKey: key,
  });

  const res = http.post(`${BASE_URL}/api/transfers`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/transfers' },
  });

  check(res, {
    '2xx': (r) => r.status === 200 || r.status === 201 || r.status === 202,
  });
}

export function handleSummary(data) {
  return {
    'summary.json': JSON.stringify(data, null, 2),
  };
}
