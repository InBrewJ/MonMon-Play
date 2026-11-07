// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.BASE_URL || process.env.MONMON_URL || 'http://127.0.0.1:9000';

describe('MonMon HTTP & REST Contract Tests (:9000)', () => {
  it('GET /health returns 200 OK with service identifier', async () => {
    const res = await fetch(`${MONMON_URL}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'ok');
    assert.equal(body.service, 'MonMon');
    assert.ok(typeof body.timestamp === 'number');
  });

  it('GET / returns 200 OK with landing page HTML', async () => {
    const res = await fetch(`${MONMON_URL}/`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /text\/html/);
    const html = await res.text();
    assert.ok(html.includes('MonMon'));
  });

  it('GET /about returns 200 OK with about page HTML', async () => {
    const res = await fetch(`${MONMON_URL}/about`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /text\/html/);
    const html = await res.text();
    assert.ok(html.includes('MonMon'));
  });

  it('GET /api/user/accessStatus returns unauthenticated contract for guest', async () => {
    const res = await fetch(`${MONMON_URL}/api/user/accessStatus`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.authenticated, false);
    assert.equal(body.approved, false);
  });

  it('GET /api/legmon/status proxies LegMon status successfully', async () => {
    const res = await fetch(`${MONMON_URL}/api/legmon/status`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.service, 'LegMon');
    assert.ok(typeof body.hasError === 'boolean');
  });

  it('GET /api/legmon/logs proxies LegMon log entries', async () => {
    const res = await fetch(`${MONMON_URL}/api/legmon/logs`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.service, 'LegMon');
    assert.ok(Array.isArray(body.logs));
  });

  it('POST /api/statement/webhook accepts statement webhook and returns ok', async () => {
    const payload = {
      userId: 'contract-test-user',
      jobId: `stmt_test_${Date.now()}`,
      bank: 'NatWest',
      startDate: '2026-10-01',
      endDate: '2026-10-31',
      openingBalance: 1500.0,
      closingBalance: 1250.0,
      transactions: [
        {
          date: '2026-10-15',
          description: 'Tesco Groceries',
          amount: -45.50,
          category: 'Groceries'
        }
      ]
    };

    const res = await fetch(`${MONMON_URL}/api/statement/webhook?userId=contract-test-user`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'ok');
  });

  it('Protected views redirect unauthenticated users to OIDC login', async () => {
    const protectedPaths = [
      '/myOutgoings',
      '/myAccounts',
      '/myBalances',
      '/myIncomings',
      '/spog',
      '/savingsPlan',
      '/netWorth'
    ];

    for (const path of protectedPaths) {
      const res = await fetch(`${MONMON_URL}${path}`, { redirect: 'manual' });
      // Should either be 302/303 redirecting to OIDC callback/Keycloak, or 401 unauthorized
      assert.ok(
        [302, 303, 401].includes(res.status),
        `Expected ${path} to redirect or require auth, got ${res.status}`
      );
    }
  });
});
