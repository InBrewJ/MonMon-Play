// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://127.0.0.1:9001';
const CORE_URL = process.env.CORE_URL || MONMON_URL;

describe('Balance Soft Delete, Global Archival Filtering & Waste Bin Recovery', () => {
  it('soft-deletes balances, excludes archived rows across SPOG/NetWorth/Transfers/Erratic/Balances, and recovers via /wasteBin', async () => {
    const user = `bal_softdel_${Date.now()}`;
    const cookieHeader = { Cookie: `user_id=${user}; authenticated=true` };

    // 1. Create a DEBIT account
    const accRes = await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Monzo SoftDel Test',
        type: 'DEBIT',
        userId: user,
        accountLimit: 100,
        interestRate: 0
      })
    });
    assert.equal(accRes.status, 200);
    const acc = await accRes.json();

    // 2. Record a baseline balance (£500.00) and a newer erroneous spike (-£2167.77)
    const nowSec = Math.floor(Date.now() / 1000);
    const mkBal = async (value, timestamp) => {
      const r = await fetch(`${CORE_URL}/internal/balances`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ accountId: acc.id, userId: user, value, timestamp })
      });
      assert.equal(r.status, 200);
      return r.json();
    };
    const goodBal = await mkBal(500.0, nowSec - 3600);
    const badBal = await mkBal(-2167.77, nowSec);

    // 3. Before soft delete: SPOG, Net Worth, Erratic Balances, and /internal/balances all see -£2167.77
    const balancesBefore = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    assert.equal(balancesBefore.length, 2);

    const spogBefore = await (await fetch(`${MONMON_URL}/api/spog/metrics?userId=${user}`)).json();
    assert.ok(
      Math.abs(spogBefore.totalAvailableDebit - (-2167.77)) < 0.05,
      `expected SPOG totalAvailableDebit -2167.77 before delete, got ${spogBefore.totalAvailableDebit}`
    );

    const erraticBefore = await (await fetch(`${MONMON_URL}/api/balances/erratic?userId=${user}&threshold=1000`)).json();
    assert.ok(erraticBefore.some(e => e.currBalanceId === badBal.id), 'erroneous jump must be detected before soft delete');

    // 4. Soft-delete the erroneous balance via DELETE /balance/:id/delete
    const delRes = await fetch(`${MONMON_URL}/balance/${badBal.id}/delete`, {
      method: 'DELETE',
      headers: { ...cookieHeader, Accept: 'application/json' }
    });
    assert.equal(delRes.status, 200, 'DELETE /balance/:id/delete must succeed');

    // 5. Verify archived balance is excluded EVERYWHERE
    const balancesAfter = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    assert.equal(balancesAfter.length, 1, 'archived balance must be excluded from /internal/balances');
    assert.equal(balancesAfter[0].id, goodBal.id);

    const spogAfter = await (await fetch(`${MONMON_URL}/api/spog/metrics?userId=${user}`)).json();
    assert.ok(
      Math.abs(spogAfter.totalAvailableDebit - 500.0) < 0.05,
      `SPOG totalAvailableDebit must revert to 500.00, got ${spogAfter.totalAvailableDebit}`
    );

    const nwAfter = await (await fetch(`${MONMON_URL}/api/networth?userId=${user}`)).json();
    const nwCash = (nwAfter.cashAccounts || []).find(a => a.id === acc.id);
    assert.ok(nwCash, 'account must appear in Net Worth cashAccounts');
    assert.ok(Math.abs(nwCash.balance - 500.0) < 0.05, `Net Worth must revert to 500.00, got ${nwCash.balance}`);

    const erraticAfter = await (await fetch(`${MONMON_URL}/api/balances/erratic?userId=${user}&threshold=1000`)).json();
    assert.equal(erraticAfter.length, 0, 'archived balance must be excluded from erratic balances');

    const transfersAfter = await (await fetch(`${MONMON_URL}/api/transfers`, { headers: cookieHeader })).json();
    const liquidAfter = transfersAfter.nextPayday?.availableLiquidFunds ?? transfersAfter.lastPayday?.availableLiquidFunds;
    assert.ok(
      Math.abs(liquidAfter - 500.0) < 0.05,
      `Transfers available liquid funds must revert to 500.00, got ${liquidAfter}`
    );

    const myBalancesHtml = await (await fetch(`${MONMON_URL}/myBalances`, { headers: cookieHeader })).text();
    assert.ok(!myBalancesHtml.includes('-2167.77'), '/myBalances page must not display archived balance');

    // 6. Verify archived balance appears in /api/waste-bin and /wasteBin?entity_type=balance
    const bin = await (await fetch(`${MONMON_URL}/api/waste-bin`, { headers: cookieHeader })).json();
    const binned = (bin.items || []).find(i => i.entity_type === 'balance' && i.id === badBal.id);
    assert.ok(binned, 'soft-deleted balance must appear in /api/waste-bin with entity_type=balance');
    assert.equal(binned.entity_label, 'Balance');
    assert.ok(binned.name.includes('Monzo SoftDel Test') && binned.name.includes('-£2167.77'), `unexpected waste bin title: ${binned.name}`);

    const wbHtml = await (await fetch(`${MONMON_URL}/wasteBin?entity_type=balance`, { headers: cookieHeader })).text();
    assert.ok(wbHtml.includes('Balances (1)'), '/wasteBin must display Balances filter count');
    assert.ok(wbHtml.includes(`data-entity-type="balance" data-entity-id="${badBal.id}"`), '/wasteBin HTML must render the balance card');

    // 7. Recover via POST /api/waste-bin/balance/:id/recover and verify restoration
    const recRes = await fetch(`${MONMON_URL}/api/waste-bin/balance/${badBal.id}/recover`, {
      method: 'POST',
      headers: { ...cookieHeader, Accept: 'application/json' }
    });
    assert.equal(recRes.status, 200);

    const binAfterRecover = await (await fetch(`${MONMON_URL}/api/waste-bin`, { headers: cookieHeader })).json();
    assert.equal((binAfterRecover.items || []).filter(i => i.entity_type === 'balance' && i.id === badBal.id).length, 0, 'recovered balance must leave /api/waste-bin');

    const balancesRecovered = await (await fetch(`${CORE_URL}/internal/balances?userId=${user}`)).json();
    assert.equal(balancesRecovered.length, 2, 'recovered balance must reappear in /internal/balances');

    const spogRecovered = await (await fetch(`${MONMON_URL}/api/spog/metrics?userId=${user}`)).json();
    assert.ok(
      Math.abs(spogRecovered.totalAvailableDebit - (-2167.77)) < 0.05,
      `recovered balance must be active again in SPOG, got ${spogRecovered.totalAvailableDebit}`
    );
  });
});
