// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('SPOG Domain & Metrics Contract Tests', () => {

  it('GET /api/spog/metrics returns complete financial projection for user', async () => {
    const res = await fetch(`${MONMON_URL}/api/spog/metrics?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const data = await res.json();

    // Verify presence of all core SPOG metrics
    assert.ok(typeof data.incomingTotal === 'number', 'incomingTotal must be a number');
    assert.ok(typeof data.outgoingTotal === 'number', 'outgoingTotal must be a number');
    assert.ok(typeof data.surplus === 'number', 'surplus must be a number');
    assert.ok(typeof data.maxPerDay === 'number', 'maxPerDay must be a number');
    assert.ok(typeof data.daysBetweenPaydays === 'number', 'daysBetweenPaydays must be a number');

    assert.ok(typeof data.completedOutgoings === 'number', 'completedOutgoings must be a number');
    assert.ok(typeof data.pendingOutgoings === 'number', 'pendingOutgoings must be a number');

    assert.ok(typeof data.totalAvailableDebit === 'number', 'totalAvailableDebit must be a number');
    assert.ok(typeof data.totalAvailableCredit === 'number', 'totalAvailableCredit must be a number');
    assert.ok(typeof data.creditBalance === 'number', 'creditBalance must be a number');
    assert.ok(typeof data.creditLimit === 'number', 'creditLimit must be a number');
    assert.ok(typeof data.creditUsage === 'number', 'creditUsage must be a number');

    assert.ok(typeof data.liquidSavingsPot === 'number', 'liquidSavingsPot must be a number');
    assert.ok(typeof data.totalSavingsPot === 'number', 'totalSavingsPot must be a number');

    // Business Logic Invariants
    assert.ok(data.daysBetweenPaydays > 0 && data.daysBetweenPaydays <= 31, 'Days in pay cycle must be 1..31');
    assert.ok(data.creditUsage >= 0 && data.creditUsage <= 100, 'Credit usage % must be between 0 and 100');
    assert.equal(
      Math.round((data.completedOutgoings + data.pendingOutgoings) * 100) / 100,
      Math.round(data.outgoingTotal * 100) / 100,
      'Completed outgoings + pending outgoings must equal total outgoings'
    );
  });

  it('GET /api/spog/spending-pace returns baseline diff and daily overspend tracking', async () => {
    const res = await fetch(`${MONMON_URL}/api/spog/spending-pace?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.ok(typeof data.spentThisMonth === 'number');
    assert.ok(typeof data.actualDailySpend === 'number');
    assert.ok(typeof data.baselineDiffTotal === 'number');
    assert.ok(typeof data.isBehindBaseline === 'boolean');
    assert.ok(typeof data.absoluteBaselineDiffTotal === 'number');
    assert.equal(data.absoluteBaselineDiffTotal, Math.abs(data.baselineDiffTotal));
  });

});
