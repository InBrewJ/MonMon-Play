// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('Monzo Dig Contract Tests', () => {

  it('GET /api/dig/data returns transactions, histogram, cycles, and limit metadata', async () => {
    const res = await fetch(`${MONMON_URL}/api/dig/data?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.ok(Array.isArray(data.transactions), 'transactions must be an array');
    assert.ok(Array.isArray(data.histogram), 'histogram must be an array');
    assert.ok(data.cycles, 'cycles must be present');
    assert.ok(typeof data.cycles.nominalPayday === 'number', 'nominalPayday must be a number');
    assert.ok(typeof data.totalTransactions === 'number', 'totalTransactions must be a number');
    assert.ok(typeof data.limit === 'number', 'limit must be a number');
    assert.ok(typeof data.limitReached === 'boolean', 'limitReached must be a boolean');
  });

  it('Ingesting geotagged webhook reflects in /api/dig/data', async () => {
    const txId = `tx_dig_${Date.now()}`;

    // 1. Ingest transaction with latitude/longitude via webhook endpoint
    const webhookPayload = {
      type: 'transaction.created',
      data: {
        id: txId,
        amount: -1850, // -£18.50
        currency: 'GBP',
        created: new Date().toISOString(),
        description: 'Dishoom Shoreditch',
        category: 'eating_out',
        merchant: {
          id: 'merch_dishoom_01',
          name: 'Dishoom',
          emoji: '🍛',
          category: 'eating_out',
          address: {
            formatted: '7 Boundary St, London E2 7JE, UK',
            latitude: 51.5255,
            longitude: -0.0772,
          },
        },
      },
    };

    const webhookRes = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${TEST_USER}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(webhookPayload),
    });
    assert.equal(webhookRes.status, 200);

    // 2. Query /api/dig/data and verify geotagged transaction appears
    const digRes = await fetch(`${MONMON_URL}/api/dig/data?userId=${TEST_USER}`);
    assert.equal(digRes.status, 200);
    const digData = await digRes.json();

    const found = digData.transactions.find((t) => t.merchant === 'Dishoom' || t.amount === 18.50);
    assert.ok(found, 'Geotagged transaction must be present in /api/dig/data transactions');
    assert.equal(found.amount, 18.50);
    assert.equal(found.category, 'eating_out');
    assert.ok(Math.abs(found.lat - 51.5255) < 0.001);
    assert.ok(Math.abs(found.lng - (-0.0772)) < 0.001);
  });
});
