// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('Statement Processing & LegMon Bridge Contract Tests', () => {

  it('GET /api/legmon/status proxies LegMon status contract', async () => {
    const res = await fetch(`${MONMON_URL}/api/legmon/status`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.equal(data.service, 'LegMon');
    assert.ok(['ok', 'warning', 'offline'].includes(data.status), 'status must be a recognized service status');
  });

  it('GET /api/legmon/logs proxies LegMon log stream', async () => {
    const res = await fetch(`${MONMON_URL}/api/legmon/logs`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.equal(data.service, 'LegMon');
    assert.ok(Array.isArray(data.logs), 'logs must be an array');
  });

  it('POST /api/statement/webhook creates pending statement and supports preview/confirm', async () => {
    const jobId = `stmt_job_${Date.now()}`;
    const webhookPayload = {
      userId: TEST_USER,
      jobId,
      bank: 'Barclays Test Bank',
      startDate: '2026-10-01',
      endDate: '2026-10-31',
      openingBalance: 1200.0,
      closingBalance: 1450.0,
      transactions: [
        {
          date: '2026-10-05',
          description: 'Client Payment',
          amount: 350.0,
          type: 'CREDIT',
        },
        {
          date: '2026-10-18',
          description: 'Office Supplies',
          amount: -100.0,
          type: 'DEBIT',
        },
      ],
    };

    // 1. Ingest statement
    const ingestRes = await fetch(`${MONMON_URL}/api/statement/webhook?userId=${TEST_USER}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(webhookPayload),
    });
    assert.equal(ingestRes.status, 200);
    const ingestResult = await ingestRes.json();
    assert.equal(ingestResult.status, 'ok');
    assert.ok(ingestResult.statementId, 'Must return statementId');
    const statementId = ingestResult.statementId;

    // 2. Preview statement
    const previewRes = await fetch(`${MONMON_URL}/api/bank/statement/${statementId}/preview?userId=${TEST_USER}`);
    assert.equal(previewRes.status, 200);
    const previewData = await previewRes.json();
    assert.equal(previewData.bank, 'Barclays Test Bank');
    assert.equal(previewData.transactions.length, 2);

    // 3. Confirm statement (matches to account and transfers transactions)
    const confirmRes = await fetch(`${MONMON_URL}/api/bank/statement/${statementId}/confirm?userId=${TEST_USER}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accountId: 0,
      }),
    });
    assert.equal(confirmRes.status, 200);
    const confirmResult = await confirmRes.json();
    assert.equal(confirmResult.status, 'confirmed');

    // 4. Verify transactions are now queryable in /bank/transactions
    const txRes = await fetch(`${MONMON_URL}/bank/transactions?userId=${TEST_USER}`);
    assert.equal(txRes.status, 200);
    const txs = await txRes.json();
    const found = txs.find((t) => t.description === 'Client Payment');
    assert.ok(found, 'Client Payment transaction should appear in bank transactions');
  });
});
