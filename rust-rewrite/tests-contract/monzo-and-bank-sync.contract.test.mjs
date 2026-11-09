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
    assert.ok(typeof data.hasActiveWebhooks === 'boolean', 'hasActiveWebhooks must be boolean');
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

  it('POST /api/monzo/webhook creates an "available" balance for the account', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `monzo_avail_${Date.now()}`;
    const created = await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Monzo Avail Test', type: 'DEBIT', userId: user, accountLimit: 0, interestRate: 0 })
    });
    assert.ok(created.ok, 'account creation must succeed');
    const debit = await created.json();

    const listBalances = async () =>
      (await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json())
        .filter(b => b.accountId === debit.id);
    const before = (await listBalances()).length;

    const res = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'transaction.created',
        data: {
          id: `tx_avail_${Date.now()}`,
          account_id: 'acc_monzo_test_123',
          amount: -450,
          account_balance: 123456, // £1234.56 available after this transaction
          created: new Date().toISOString(),
          description: 'Available Balance Test',
          category: 'groceries'
        }
      })
    });
    assert.equal(res.status, 200);

    const after = await listBalances();
    assert.equal(after.length, before + 1, 'webhook must create one new balance row');
    const latest = after.sort((a, b) => b.timestamp - a.timestamp)[0];
    assert.ok(Math.abs(latest.value - 1234.56) < 0.005, `latest available should be 1234.56, got ${latest.value}`);
  });

  it('LegMon statement webhook + confirm records the closing balance as an "available"', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `stmt_avail_${Date.now()}`;
    const acc = await (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Stmt Avail Test', type: 'DEBIT', userId: user, accountLimit: 0, interestRate: 0 })
    })).json();

    const hook = await fetch(`${MONMON_URL}/api/statement/webhook?userId=${user}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: user, jobId: `job_${Date.now()}`, bank: 'TestBank',
        startDate: '2026-10-01', endDate: '2026-10-31',
        openingBalance: 800.0, closingBalance: 742.5, targetAccountId: acc.id,
        transactions: [{ date: '2026-10-05', description: 'Shop', amount: -57.5 }]
      })
    });
    assert.equal(hook.status, 200);
    const { statementId } = await hook.json();

    const confirm = await fetch(`${MONMON_URL}/api/bank/statement/${statementId}/confirm?userId=${user}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ accountId: acc.id })
    });
    assert.equal(confirm.status, 200);

    const balances = (await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json())
      .filter(b => b.accountId === acc.id);
    assert.ok(balances.length >= 1, 'confirming a statement must create an available balance');
    const latest = balances.sort((a, b) => b.timestamp - a.timestamp)[0];
    assert.ok(Math.abs(latest.value - 742.5) < 0.005, `expected 742.5, got ${latest.value}`);
  });

  it('POST /api/monzo/webhook for Flex records available credit (limit minus owed)', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `flex_avail_${Date.now()}`;
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();
    const debit = await mk('DEBIT', 'Monzo Current', 0);
    const credit = await mk('CREDIT', 'Monzo Flex', 1000);

    const res = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'transaction.created',
        data: {
          id: `tx_flex_${Date.now()}`, account_id: 'acc_flex_test', amount: -2500,
          account_balance: -25000, // £250 owed on Flex
          created: new Date().toISOString(), description: 'Flex Test', category: 'shopping',
          metadata: { monzo_flex_id: 'flex_123' }
        }
      })
    });
    assert.equal(res.status, 200);

    const balances = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    assert.equal(balances.filter(b => b.accountId === debit.id).length, 0, 'debit account must be untouched');
    const flex = balances.filter(b => b.accountId === credit.id);
    assert.equal(flex.length, 1, 'Flex webhook must create one balance on the CREDIT account');
    assert.ok(Math.abs(flex[0].value - 750) < 0.005, `expected available 750, got ${flex[0].value}`);
  });

  it('POST /bank/monzo/balances/refresh responds with JSON and refreshes nothing when Monzo is not connected', async () => {
    const user = `refresh_${Date.now()}`;
    const res = await fetch(`${MONMON_URL}/bank/monzo/balances/refresh?userId=${user}`, {
      method: 'POST',
      headers: { Accept: 'application/json' }
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(Array.isArray(body.refreshed), 'refreshed must be an array');
    assert.equal(body.refreshed.length, 0);
  });

  it('POST /api/monzo/webhook picks the right account when the user has many (name preference, DEBIT/CREDIT only)', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `multi_avail_${Date.now()}`;
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();
    const pot = await mk('SHORT_TERM_SAVINGS', 'Monzo Pot', 0);
    const lloyds = await mk('DEBIT', 'Lloyds', 0);
    const paypal = await mk('CREDIT', 'Paypal Credit', 2500);
    const flex = await mk('CREDIT', 'Flex', 2500);

    const post = (data) => fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'transaction.created', data: { account_id: 'acc_x', created: new Date().toISOString(), amount: -100, ...data } })
    });
    await post({ id: `tx_m1_${Date.now()}`, account_balance: 50000 });
    await post({ id: `tx_m2_${Date.now()}`, account_balance: -100000, metadata: { monzo_flex_id: 'f' } });

    const balances = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    const on = (a) => balances.filter(b => b.accountId === a.id);
    assert.equal(on(pot).length, 0, 'savings account must not receive Monzo debit balance');
    assert.equal(on(lloyds).length, 1, 'DEBIT account must receive the debit balance');
    assert.equal(on(paypal).length, 0, 'non-Flex credit must be untouched');
    assert.equal(on(flex).length, 0 + 1, 'Flex-named CREDIT account must receive the Flex balance');
    assert.ok(Math.abs(on(flex)[0].value - 1500) < 0.005);
  });

});
