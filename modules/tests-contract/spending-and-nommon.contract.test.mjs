// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('Spending Pace & NomMon Contract Tests', () => {

  it('GET /api/spog/spending-pace returns spending pace and baseline variance', async () => {
    const res = await fetch(`${MONMON_URL}/api/spog/spending-pace?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const pace = await res.json();

    assert.ok(typeof pace.spentThisMonth === 'number', 'spentThisMonth must be number');
    assert.ok(typeof pace.actualDailySpend === 'number', 'actualDailySpend must be number');
    assert.ok(typeof pace.baselineDiffTotal === 'number', 'baselineDiffTotal must be number');
    assert.ok(typeof pace.isBehindBaseline === 'boolean', 'isBehindBaseline must be boolean');
  });

  it('GET /api/nommon/plan returns meal plan object with lock state', async () => {
    const res = await fetch(`${MONMON_URL}/api/nommon/plan?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.ok(typeof data.configured === 'boolean', 'configured flag must be boolean');
    assert.ok(typeof data.locked === 'boolean', 'locked flag must be boolean');
  });

  it('POST /api/nommon/lock locks plan and returns confirmation', async () => {
    const lockPayload = {
      plan: {
        title: "Test Week Meal Plan",
        shoppingList: [
          { item: "Whole Milk 2L", category: "Dairy", price: 1.50, checked: false },
          { item: "Brown Bread 800g", category: "Bakery", price: 1.10, checked: false }
        ]
      },
      budget: 45.0,
      people: 2,
      userId: TEST_USER
    };

    const lockRes = await fetch(`${MONMON_URL}/api/nommon/lock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(lockPayload)
    });
    assert.equal(lockRes.status, 200);
    const lockData = await lockRes.json();
    assert.equal(lockData.success, true);
    assert.equal(lockData.locked, true);

    // Verify GET reflects locked plan
    const getRes = await fetch(`${MONMON_URL}/api/nommon/plan?userId=${TEST_USER}`);
    const planData = await getRes.json();
    assert.equal(planData.locked, true);
  });

  it('POST /api/nommon/unlock unlocks the plan cleanly', async () => {
    const unlockRes = await fetch(`${MONMON_URL}/api/nommon/unlock?userId=${TEST_USER}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: TEST_USER })
    });
    assert.equal(unlockRes.status, 200);
    const unlockData = await unlockRes.json();
    assert.equal(unlockData.success, true);
    assert.equal(unlockData.locked, false);

    // Verify plan is unlocked
    const getRes = await fetch(`${MONMON_URL}/api/nommon/plan?userId=${TEST_USER}`);
    const planData = await getRes.json();
    assert.equal(planData.locked, false);
  });

});
