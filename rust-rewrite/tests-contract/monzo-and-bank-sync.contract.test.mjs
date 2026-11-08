// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('Monzo OAuth & Webhooks Contract Tests', () => {

  it('GET /bank/monzo/webhooks/status returns connection and webhook state', async () => {
    const res = await fetch(`${MONMON_URL}/bank/monzo/webhooks/status?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.ok(typeof data.connected === 'boolean', 'connected must be boolean');
    assert.ok(typeof data.webhooksRegistered === 'boolean', 'webhooksRegistered must be boolean');
  });

  it('POST /api/monzo/webhook ingests transaction and records in bank_transaction', async () => {
    const testTxId = `tx_contract_${Date.now()}`;
    const webhookPayload = {
      type: "transaction.created",
      data: {
        id: testTxId,
        account_id: "acc_monzo_test_123",
        amount: -1250, // -1250 pence = £12.50 expense in Monzo API convention
        created: new Date().toISOString(),
        description: "Contract Test Coffee",
        category: "eating_out",
        merchant: {
          name: "Antigravity Coffee Bar",
          category: "eating_out",
          emoji: "☕",
          address: {
            formatted: "10 Downing Street, London",
            latitude: 51.5034,
            longitude: -0.1276
          }
        }
      }
    };

    const res = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${TEST_USER}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(webhookPayload)
    });
    assert.equal(res.status, 200);
    const result = await res.json();
    assert.equal(result.status, 'ok');

    // Verify transaction is queryable
    const txRes = await fetch(`${MONMON_URL}/bank/transactions?userId=${TEST_USER}`);
    assert.equal(txRes.status, 200);
    const txList = await txRes.json();
    assert.ok(Array.isArray(txList), 'transactions must be an array');
    const found = txList.find(t => t.externalId === testTxId || t.description === "Antigravity Coffee Bar");
    assert.ok(found, 'Ingested Monzo transaction must be present in bank transactions');
  });

});
