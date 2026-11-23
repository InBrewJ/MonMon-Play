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

  it('POST /api/monzo/webhook separates Flex when is_flex_direct_virtual_card_tx is string "true" (real Monzo payload format)', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `flex_str_bool_${Date.now()}`;
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();
    const debit = await mk('DEBIT', 'Monzo', 100);
    const flex = await mk('CREDIT', 'Flex', 2500);

    const res = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'transaction.created',
        data: {
          id: `tx_flex_str_${Date.now()}`,
          account_id: 'acc_0000BAnOMUDOHXnBCLSLZ6',
          amount: -4905,
          account_balance: -216777, // £2167.77 owed on Flex -> £332.23 available
          created: new Date().toISOString(),
          description: "Sainsbury's",
          category: 'groceries',
          metadata: { is_flex_direct_virtual_card_tx: 'true' }
        }
      })
    });
    assert.equal(res.status, 200);

    const balances = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    assert.equal(balances.filter(b => b.accountId === debit.id).length, 0, 'Monzo DEBIT must NEVER receive a Flex balance');
    const flexBals = balances.filter(b => b.accountId === flex.id);
    assert.equal(flexBals.length, 1, 'Flex CREDIT must receive the converted available balance');
    assert.ok(Math.abs(flexBals[0].value - 332.23) < 0.01, `expected Flex available 332.23, got ${flexBals[0].value}`);
  });

  it('POST /api/monzo/webhook with accountType=flex routes to Flex even without metadata', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `flex_hint_${Date.now()}`;
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();
    const debit = await mk('DEBIT', 'Monzo', 100);
    const flex = await mk('CREDIT', 'Flex', 2500);

    const res = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}&accountType=flex`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'transaction.created',
        data: {
          id: `tx_flex_hint_${Date.now()}`,
          account_id: 'acc_flex_unknown',
          amount: -4905,
          account_balance: -216777,
          created: new Date().toISOString(),
          description: "Sainsbury's",
          category: 'groceries'
        }
      })
    });
    assert.equal(res.status, 200);

    const balances = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    assert.equal(balances.filter(b => b.accountId === debit.id).length, 0, 'Monzo DEBIT must be untouched');
    const flexBals = balances.filter(b => b.accountId === flex.id);
    assert.equal(flexBals.length, 1, 'Flex CREDIT must receive the balance');
    assert.ok(Math.abs(flexBals[0].value - 332.23) < 0.01, `expected 332.23, got ${flexBals[0].value}`);
  });

  it('POST /api/monzo/webhook deduplicates repeated deliveries of the same transaction ID and ignores declined txs', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `dedupe_declined_${Date.now()}`;
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();
    const flex = await mk('CREDIT', 'Flex', 2500);

    const txId = `tx_dup_${Date.now()}`;
    const payload = {
      type: 'transaction.created',
      data: {
        id: txId,
        account_id: 'acc_flex_dup',
        amount: -1500,
        account_balance: -50000,
        created: new Date().toISOString(),
        description: 'Scotmid',
        category: 'groceries',
        metadata: { monzo_flex_id: 'monzoflex_1' }
      }
    };

    // Deliver the exact same webhook 3 times (simulating Monzo retry / duplicate delivery)
    for (let i = 0; i < 3; i++) {
      const r = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}&accountType=flex`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      assert.equal(r.status, 200);
    }

    // Also deliver a declined transaction
    const decRes = await fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}&accountType=flex`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'transaction.created',
        data: {
          id: `tx_declined_${Date.now()}`,
          account_id: 'acc_flex_dup',
          amount: -9999,
          account_balance: -99999,
          decline_reason: 'INSUFFICIENT_FUNDS',
          created: new Date().toISOString(),
          description: 'Declined Shop',
          metadata: { monzo_flex_id: 'monzoflex_1' }
        }
      })
    });
    assert.equal(decRes.status, 200);

    const balances = (await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json())
      .filter(b => b.accountId === flex.id);
    assert.equal(balances.length, 1, `expected exactly 1 balance (deduped, declined skipped), got ${balances.length}`);
    assert.ok(Math.abs(balances[0].value - 2000) < 0.01);
  });

  it('POST /api/statement/webhook auto-matches Monzo Flex to CREDIT Flex and Monzo to DEBIT Monzo', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `stmt_monzo_flex_${Date.now()}`;
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();
    const monzoDebit = await mk('DEBIT', 'Monzo', 100);
    const monzoFlex = await mk('CREDIT', 'Flex', 2500);

    const postStmt = async (bank) => {
      const r = await fetch(`${MONMON_URL}/api/statement/webhook?userId=${user}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: user, jobId: `job_${bank}_${Date.now()}`, bank,
          startDate: '2026-10-01', endDate: '2026-10-31',
          openingBalance: 100, closingBalance: 90,
          transactions: [{ date: '2026-10-05', description: 'Shop', amount: -10 }]
        })
      });
      assert.equal(r.status, 200);
      return r.json();
    };

    const flexHook = await postStmt('Monzo Flex');
    const debitHook = await postStmt('Monzo');

    const pending = await (await fetch(`${MONMON_URL}/api/bank/statement/pending?userId=${user}`)).json();
    const list = Array.isArray(pending) ? pending : (pending.pendingStatements || pending.statements || []);
    const flexStmt = list.find(s => s.id === flexHook.statementId);
    const debitStmt = list.find(s => s.id === debitHook.statementId);
    assert.ok(flexStmt, 'Flex statement must be pending');
    assert.ok(debitStmt, 'Monzo debit statement must be pending');
    assert.equal(flexStmt.matchedAccountId, monzoFlex.id, 'Monzo Flex statement must auto-match Flex CREDIT, not Monzo DEBIT');
    assert.equal(debitStmt.matchedAccountId, monzoDebit.id, 'Monzo statement must auto-match Monzo DEBIT, not Flex CREDIT');
  });

  it('GET /bank renders expandable default-closed Configured Ledger Accounts with Monzo/Flex pinned at top and working ABL/TBL toggle', async () => {
    const CORE_URL = process.env.CORE_URL || MONMON_URL;
    const user = `bank_accounts_ui_${Date.now()}`;
    const cookieHeader = { Cookie: `user_id=${user}; authenticated=true` };
    const mk = async (type, name, limit) => (await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, type, userId: user, accountLimit: limit, interestRate: 0 })
    })).json();

    const amex = await mk('CREDIT', 'Amex Platinum', 3000);
    const barclays = await mk('DEBIT', 'Barclays Current', 0);
    const flex = await mk('CREDIT', 'Flex', 2500);
    const monzo = await mk('DEBIT', 'Monzo', 100);

    const html = await (await fetch(`${MONMON_URL}/bank?userId=${user}`, { headers: cookieHeader })).text();

    // Expandable and default closed (<details ...> without `open`)
    assert.ok(html.includes('<details id="configured-ledger-accounts-details">'), 'Configured Ledger Accounts must be a default-closed <details> element');
    assert.ok(html.includes('Configured Ledger Accounts (4)'), 'Heading must show total account count');
    assert.ok(html.includes('2 Monzo'), 'Collapsed summary must show Monzo count pill');

    // Monzo Debit and Flex must appear BEFORE Amex and Barclays even when Monzo is not connected
    const posMonzo = html.indexOf(`data-bank-account-id="${monzo.id}"`);
    const posFlex = html.indexOf(`data-bank-account-id="${flex.id}"`);
    const posAmex = html.indexOf(`data-bank-account-id="${amex.id}"`);
    const posBarclays = html.indexOf(`data-bank-account-id="${barclays.id}"`);
    assert.ok(posMonzo > 0 && posFlex > 0 && posAmex > 0 && posBarclays > 0, 'all 4 accounts must render in Configured Ledger Accounts');
    assert.ok(posMonzo < posFlex, 'Monzo Debit must appear before Monzo Flex');
    assert.ok(posFlex < posAmex && posFlex < posBarclays, 'Monzo & Flex must be pinned above non-Monzo accounts');
    assert.ok(posAmex < posBarclays, 'non-Monzo accounts must be sorted alphabetically');
    assert.ok(html.includes('Monzo (Not Connected)'), 'disconnected Monzo accounts must clearly show Not Connected status');

    // Toggle ledger mode round-trip (ABL <-> TBL)
    const toggle1 = await fetch(`${MONMON_URL}/bank/account/${monzo.id}/toggle-mode`, {
      method: 'POST',
      headers: { ...cookieHeader, Accept: 'application/json' }
    });
    assert.equal(toggle1.status, 200);
    const t1 = await toggle1.json();
    assert.ok(t1.ledgerMode === 'TBL' || t1.ledgerMode === 'ABL', `unexpected ledgerMode: ${t1.ledgerMode}`);

    const toggle2 = await fetch(`${MONMON_URL}/bank/account/${monzo.id}/toggle-mode`, {
      method: 'POST',
      headers: { ...cookieHeader, Accept: 'application/json' }
    });
    assert.equal(toggle2.status, 200);
    const t2 = await toggle2.json();
    assert.equal(t2.ledgerMode, t1.ledgerMode === 'TBL' ? 'ABL' : 'TBL', 'second toggle must flip ledgerMode back');

    // ABL-first copy & guard: non-Monzo account cannot be switched to TBL
    assert.ok(html.includes('MonMon is ABL-first'), 'page must clearly state MonMon is ABL-first');
    // Ensure Barclays is in ABL first (test helper /internal/accounts may start in TBL), then verify toggling to TBL is rejected
    const b1 = await fetch(`${MONMON_URL}/bank/account/${barclays.id}/toggle-mode`, {
      method: 'POST',
      headers: { ...cookieHeader, Accept: 'application/json' }
    });
    if (b1.status === 200) {
      const b1Json = await b1.json();
      assert.equal(b1Json.ledgerMode, 'ABL', 'non-Monzo account may only switch back to ABL');
      const b2 = await fetch(`${MONMON_URL}/bank/account/${barclays.id}/toggle-mode`, {
        method: 'POST',
        headers: { ...cookieHeader, Accept: 'application/json' }
      });
      assert.equal(b2.status, 400, 'non-Monzo account must be blocked from switching to TBL');
    } else {
      assert.equal(b1.status, 400, 'non-Monzo account already in ABL must be blocked from switching to TBL');
    }

    // Ingest one Monzo Debit tx and one Monzo Flex tx and verify Recent Synced Transactions labels Debit vs Flex
    const postWh = (qs, data) => fetch(`${MONMON_URL}/api/monzo/webhook?userId=${user}${qs}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'transaction.created', data: { created: new Date().toISOString(), ...data } })
    });
    assert.equal((await postWh('&accountType=debit', {
      id: `tx_ui_debit_${Date.now()}`, account_id: 'acc_ui_debit', amount: -550, description: 'Debit Coffee'
    })).status, 200);
    assert.equal((await postWh('&accountType=flex', {
      id: `tx_ui_flex_${Date.now()}`, account_id: 'acc_ui_flex', amount: -4905, description: 'Flex Shop',
      metadata: { is_flex_direct_virtual_card_tx: 'true' }
    })).status, 200);

    const txJson = await (await fetch(`${MONMON_URL}/bank/transactions?userId=${user}`)).json();
    const debitTx = txJson.find(t => t.description === 'Debit Coffee');
    const flexTx = txJson.find(t => t.description === 'Flex Shop');
    assert.equal(debitTx?.monzoRail, 'DEBIT', 'Monzo debit transaction must report monzoRail=DEBIT');
    assert.equal(flexTx?.monzoRail, 'FLEX', 'Monzo flex transaction must report monzoRail=FLEX');

    const htmlWithTxs = await (await fetch(`${MONMON_URL}/bank?userId=${user}`, { headers: cookieHeader })).text();
    assert.ok(htmlWithTxs.includes(`data-tx-id="${debitTx.id}" data-tx-rail="DEBIT"`), 'Debit tx row must carry data-tx-rail="DEBIT"');
    assert.ok(htmlWithTxs.includes(`data-tx-id="${flexTx.id}" data-tx-rail="FLEX"`), 'Flex tx row must carry data-tx-rail="FLEX"');
  });

});
