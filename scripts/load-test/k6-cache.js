import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 30);
const DURATION_SEC = Number(__ENV.DURATION_SEC || 20);
const GAP_SEC = 2;
const SAMPLE_SIZE = Number(__ENV.SAMPLE_SIZE || 100);
const WARMUP = __ENV.WARMUP === 'true';

const PAST_FROM = '2026-01-01';
const PAST_TO = '2026-01-31';

const allAccounts = new SharedArray('bench_accounts', () =>
  JSON.parse(open('./.bench.accounts.json'))
);

export const latUserById = new Trend('lat_user_by_id', true);
export const latAccountById = new Trend('lat_account_by_id', true);
export const latAccountByUser = new Trend('lat_account_by_user', true);
export const latPastReportPost = new Trend('lat_past_report_post', true);
export const latReportById = new Trend('lat_report_by_id', true);

export const reqsUserById = new Counter('reqs_user_by_id');
export const reqsAccountById = new Counter('reqs_account_by_id');
export const reqsAccountByUser = new Counter('reqs_account_by_user');
export const reqsPastReportPost = new Counter('reqs_past_report_post');
export const reqsReportById = new Counter('reqs_report_by_id');

function scenarioAt(index, execName) {
  return {
    executor: 'constant-vus',
    vus: VUS,
    duration: `${DURATION_SEC}s`,
    startTime: `${index * (DURATION_SEC + GAP_SEC)}s`,
    exec: execName,
    gracefulStop: '2s',
  };
}

export const options = {
  setupTimeout: '120s',
  noConnectionReuse: false,
  scenarios: {
    user_by_id: scenarioAt(0, 'testUserById'),
    account_by_id: scenarioAt(1, 'testAccountById'),
    account_by_user: scenarioAt(2, 'testAccountByUser'),
    past_report_post: scenarioAt(3, 'testPastReportPost'),
    report_by_id: scenarioAt(4, 'testReportById'),
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const sample = allAccounts.slice(0, Math.min(SAMPLE_SIZE, allAccounts.length));
  const reportIds = [];
  const reportPayload = JSON.stringify({ fromDate: PAST_FROM, toDate: PAST_TO });
  const jsonHeaders = { 'Content-Type': 'application/json' };

  for (let i = 0; i < sample.length; i++) {
    const item = sample[i];

    // Ensure past_account_reports row exists in DB for both cache-off and cache-on runs.
    const rPost = http.post(
      `${BASE_URL}/api/accounts/${item.accountId}/reports`,
      reportPayload,
      { headers: jsonHeaders, tags: { name: 'setup_past_report' } }
    );
    if (rPost.status !== 200) {
      throw new Error(`Setup failed for POST report accountId=${item.accountId}: ${rPost.status} ${rPost.body}`);
    }
    const reportId = rPost.json('reportId');
    reportIds.push(reportId);

    if (WARMUP) {
      http.get(`${BASE_URL}/api/users/${item.userId}`, { tags: { name: 'warmup_user' } });
      http.get(`${BASE_URL}/api/accounts/${item.accountId}`, { tags: { name: 'warmup_acct' } });
      http.get(`${BASE_URL}/api/accounts/user/${item.userId}`, { tags: { name: 'warmup_acct_user' } });
      http.get(`${BASE_URL}/api/reports/${reportId}`, { tags: { name: 'warmup_report' } });
    }
  }

  return { sample, reportIds };
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function testUserById(data) {
  const item = pick(data.sample);
  const res = http.get(`${BASE_URL}/api/users/${item.userId}`, {
    tags: { name: 'GET /api/users/{id}' },
  });
  check(res, { 'user 200': (r) => r.status === 200 });
  latUserById.add(res.timings.duration);
  reqsUserById.add(1);
}

export function testAccountById(data) {
  const item = pick(data.sample);
  const res = http.get(`${BASE_URL}/api/accounts/${item.accountId}`, {
    tags: { name: 'GET /api/accounts/{id}' },
  });
  check(res, { 'account 200': (r) => r.status === 200 });
  latAccountById.add(res.timings.duration);
  reqsAccountById.add(1);
}

export function testAccountByUser(data) {
  const item = pick(data.sample);
  const res = http.get(`${BASE_URL}/api/accounts/user/${item.userId}`, {
    tags: { name: 'GET /api/accounts/user/{userId}' },
  });
  check(res, { 'account-by-user 200': (r) => r.status === 200 });
  latAccountByUser.add(res.timings.duration);
  reqsAccountByUser.add(1);
}

export function testPastReportPost(data) {
  const item = pick(data.sample);
  const res = http.post(
    `${BASE_URL}/api/accounts/${item.accountId}/reports`,
    JSON.stringify({ fromDate: PAST_FROM, toDate: PAST_TO }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'POST /api/accounts/{accountId}/reports (past)' },
    }
  );
  check(res, { 'past report 200': (r) => r.status === 200 });
  latPastReportPost.add(res.timings.duration);
  reqsPastReportPost.add(1);
}

export function testReportById(data) {
  const reportId = pick(data.reportIds);
  const res = http.get(`${BASE_URL}/api/reports/${reportId}`, {
    tags: { name: 'GET /api/reports/{reportId}' },
  });
  check(res, { 'report 200': (r) => r.status === 200 });
  latReportById.add(res.timings.duration);
  reqsReportById.add(1);
}
