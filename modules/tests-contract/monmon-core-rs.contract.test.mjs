// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const CORE_URL = process.env.CORE_URL || 'http://127.0.0.1:9001';
const TEST_USER = `test_user_${Date.now()}`;

describe('MonMon-rs Core Primitives Contract Tests (:9001)', () => {
  let createdAccountId = 0;
  let createdOutgoingId = 0;

  it('GET /health returns 200 OK', async () => {
    const res = await fetch(`${CORE_URL}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'ok');
    assert.equal(body.service, 'monmon-rs');
  });

  it('POST /internal/account creates account in PostgreSQL', async () => {
    const res = await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Monzo Current Account',
        type: 'debit',
        userId: TEST_USER,
        accountLimit: 500.0,
        interestRate: 0.0
      })
    });

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.id > 0);
    assert.equal(body.name, 'Monzo Current Account');
    assert.equal(body.type, 'debit');
    assert.equal(body.userId, TEST_USER);
    createdAccountId = body.id;
  });

  it('GET /internal/accounts retrieves accounts for userId', async () => {
    const res = await fetch(`${CORE_URL}/internal/accounts?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(Array.isArray(body));
    assert.equal(body.length, 1);
    assert.equal(body[0].id, createdAccountId);
  });

  it('POST /internal/incoming creates incoming payment', async () => {
    const res = await fetch(`${CORE_URL}/internal/incomings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Tech Salary',
        netValue: 2850.50,
        incomingMonthDay: 25,
        userId: TEST_USER,
        accountId: createdAccountId,
        payday: true
      })
    });

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.id > 0);
    assert.equal(body.name, 'Tech Salary');
    assert.equal(body.netValue, 2850.50);
  });

  it('POST /internal/outgoing creates outgoing with pause/unpause lifecycle', async () => {
    const res = await fetch(`${CORE_URL}/internal/outgoings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: '[£9 for 24 months] Ionos VPS L (what happens if we don\'t pay?)',
        cost: 10.80,
        outgoingDay: 15,
        userId: TEST_USER,
        accountId: createdAccountId,
        bill: true,
        rent: false
      })
    });

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.id > 0);
    assert.equal(body.name, '[£9 for 24 months] Ionos VPS L (what happens if we don\'t pay?)');
    assert.equal(body.cost, 10.80);
    assert.equal(body.paused, false);
    createdOutgoingId = body.id;

    // Test Pause Outgoing
    const pauseRes = await fetch(`${CORE_URL}/internal/outgoings/${createdOutgoingId}/pause`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: TEST_USER,
        reason: 'Migrated to Raspberry Pi 5 on Greenstreet'
      })
    });
    assert.equal(pauseRes.status, 200);
    const pausedBody = await pauseRes.json();
    assert.equal(pausedBody.paused, true);
    assert.equal(pausedBody.pauseReason, 'Migrated to Raspberry Pi 5 on Greenstreet');

    // Test Unpause Outgoing
    const unpauseRes = await fetch(`${CORE_URL}/internal/outgoings/${createdOutgoingId}/unpause`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: TEST_USER
      })
    });
    assert.equal(unpauseRes.status, 200);
    const unpausedBody = await unpauseRes.json();
    assert.equal(unpausedBody.paused, false);
  });

  it('POST /internal/balance and erratic balance jump detection in PostgreSQL', async () => {
    // 1. Balance 1: £1200 at t=1000
    await fetch(`${CORE_URL}/internal/balances`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        value: 1200.0,
        timestamp: 1000,
        accountId: createdAccountId,
        userId: TEST_USER
      })
    });

    // 2. Balance 2: £3500 at t=2000 (jump of £2300!)
    await fetch(`${CORE_URL}/internal/balances`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        value: 3500.0,
        timestamp: 2000,
        accountId: createdAccountId,
        userId: TEST_USER
      })
    });

    // Query erratic balances with threshold £1000
    const erraticRes = await fetch(`${CORE_URL}/internal/balances/erratic?userId=${TEST_USER}&threshold=1000`);
    assert.equal(erraticRes.status, 200);
    const jumps = await erraticRes.json();
    assert.equal(jumps.length, 1);
    assert.equal(jumps[0].jumpAmount, 2300.0);
    assert.equal(jumps[0].prevValue, 1200.0);
    assert.equal(jumps[0].currValue, 3500.0);
    assert.equal(jumps[0].accountId, createdAccountId);
  });
});
