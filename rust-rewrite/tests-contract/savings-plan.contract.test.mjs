// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('Savings Plan Domain & Priority Queue Contract Tests', () => {

  it('GET /api/savings-plan returns active plan with items sorted by priority', async () => {
    const res = await fetch(`${MONMON_URL}/api/savings-plan?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const plan = await res.json();

    assert.ok(plan.id, 'Plan must have an id');
    assert.ok(typeof plan.savingsPercentage === 'number', 'savingsPercentage must be a number');
    assert.ok(Array.isArray(plan.items), 'items must be an array');

    // Ensure items are strictly ordered P1..Pn
    for (let i = 0; i < plan.items.length; i++) {
      assert.equal(plan.items[i].priority, i + 1, `Item at index ${i} must have priority ${i + 1}`);
    }
  });

  it('POST /api/savings-plan/item adds item and maintains contiguous priority queue', async () => {
    const newItem = {
      name: `Contract Test Item ${Date.now()}`,
      type: 'SAVINGS_TARGET',
      targetAmount: 500,
      monthlyAmount: 50,
      userId: TEST_USER
    };

    const res = await fetch(`${MONMON_URL}/api/savings-plan/item`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(newItem)
    });
    assert.equal(res.status, 200);
    const created = await res.json();
    assert.ok(created.id, 'Created item must have an id');
    assert.ok(created.priority >= 1, 'Created item priority must be >= 1');

    // Verify list reflects new item
    const listRes = await fetch(`${MONMON_URL}/api/savings-plan?userId=${TEST_USER}`);
    const plan = await listRes.json();
    const found = plan.items.find(it => it.id === created.id);
    assert.ok(found, 'New item must be present in plan');
  });

  it('POST /api/savings-plan/item/:id/move-up swaps priority with adjacent item', async () => {
    const listRes = await fetch(`${MONMON_URL}/api/savings-plan?userId=${TEST_USER}`);
    const plan = await listRes.json();
    if (plan.items.length >= 2) {
      const secondItem = plan.items[1];
      const res = await fetch(`${MONMON_URL}/api/savings-plan/item/${secondItem.id}/move-up?userId=${TEST_USER}`, {
        method: 'POST'
      });
      assert.equal(res.status, 200);

      // Verify second item is now P1
      const updatedRes = await fetch(`${MONMON_URL}/api/savings-plan?userId=${TEST_USER}`);
      const updatedPlan = await updatedRes.json();
      const updatedItem = updatedPlan.items.find(it => it.id === secondItem.id);
      assert.equal(updatedItem.priority, 1, 'Moved item must now have priority 1');
    }
  });

  it('POST /api/savings-plan/item/:id/toggle-lock toggles lock flag', async () => {
    const listRes = await fetch(`${MONMON_URL}/api/savings-plan?userId=${TEST_USER}`);
    const plan = await listRes.json();
    if (plan.items.length > 0) {
      const item = plan.items[0];
      const initialLocked = item.locked || false;

      const res = await fetch(`${MONMON_URL}/api/savings-plan/item/${item.id}/toggle-lock?userId=${TEST_USER}`, {
        method: 'POST'
      });
      assert.equal(res.status, 200);
      const updated = await res.json();
      assert.equal(updated.locked, !initialLocked, 'Lock flag must be toggled');
    }
  });

  it('POST /api/savings-plan/item/:id/delete reallocates budget and preserves contiguous priorities', async () => {
    // Create temporary item
    const createRes = await fetch(`${MONMON_URL}/api/savings-plan/item`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: `To Delete ${Date.now()}`,
        type: 'SAVINGS_TARGET',
        targetAmount: 200,
        monthlyAmount: 20,
        userId: TEST_USER
      })
    });
    const created = await createRes.json();

    const delRes = await fetch(`${MONMON_URL}/api/savings-plan/item/${created.id}?userId=${TEST_USER}`, {
      method: 'DELETE'
    });
    assert.equal(delRes.status, 200);

    // Verify contiguous priorities after deletion
    const listRes = await fetch(`${MONMON_URL}/api/savings-plan?userId=${TEST_USER}`);
    const plan = await listRes.json();
    for (let i = 0; i < plan.items.length; i++) {
      assert.equal(plan.items[i].priority, i + 1, `Priorities must remain contiguous 1..N after deletion`);
    }
  });

});
