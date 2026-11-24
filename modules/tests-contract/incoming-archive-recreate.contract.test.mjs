// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const MONMON_URL = process.env.MONMON_URL || 'http://127.0.0.1:9001';
const CORE_URL = process.env.CORE_URL || MONMON_URL;
const PG_CONTAINER = process.env.PG_CONTAINER || 'postgres-monmon';
const PG_DB = process.env.PG_DB || 'monmon-rust';
const PG_USER = process.env.PG_USER || 'postgres';

function runPsql(sql) {
  return execFileSync(
    'docker',
    ['exec', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-t', '-A', '-c', sql],
    { encoding: 'utf8' }
  ).trim();
}

describe('Incoming Archive-and-Recreate History (Real PostgreSQL + MonMon-rs)', () => {
  it('archives old incoming and creates a new active row on API & form edit without double-counting, with strict ownership & stale-ID guards', async () => {
    const user = `inc_hist_${Date.now()}`;
    const cookieHeader = { Cookie: `user_id=${user}; authenticated=true` };

    // 1. Create a DEBIT account
    const accRes = await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Primary Salary Account',
        type: 'DEBIT',
        userId: user,
        accountLimit: 0,
        interestRate: 0
      })
    });
    assert.equal(accRes.status, 200);
    const acc = await accRes.json();

    // 2. Create initial primary payday incoming (£3000 net, £4200 gross, Day 25)
    const createRes = await fetch(`${CORE_URL}/internal/incomings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Old Salary',
        netValue: 3000.0,
        grossValue: 4200.0,
        incomingMonthDay: 25,
        userId: user,
        accountId: acc.id,
        payday: true
      })
    });
    assert.equal(createRes.status, 200);
    const created = await createRes.json();
    assert.ok(created.id > 0);
    assert.equal(created.netValue, 3000.0);
    assert.equal(created.grossValue, 4200.0);

    // 3. Edit via PUT /internal/incomings/:id (omitting grossValue -> must preserve 4200.0)
    const editRes = await fetch(`${CORE_URL}/internal/incomings/${created.id}`, {
      method: 'PUT',
      headers: { ...cookieHeader, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Raised Salary',
        netValue: 3500.0,
        incomingMonthDay: 28,
        userId: user,
        accountId: acc.id,
        payday: true
      })
    });
    assert.equal(editRes.status, 200, `PUT /internal/incomings/${created.id} failed: ${editRes.status} ${await editRes.clone().text()}`);
    const updated = await editRes.json();
    assert.notEqual(updated.id, created.id, 'editing an incoming must create a new row ID instead of mutating in-place');
    assert.equal(updated.name, 'Raised Salary');
    assert.equal(updated.netValue, 3500.0);
    assert.equal(updated.grossValue, 4200.0, 'grossValue must be preserved when omitted on edit');
    assert.equal(updated.incomingMonthDay, 28);
    assert.equal(updated.archived, false);

    // 4. Active incoming list must contain ONLY the newly created row (no double-counting)
    const activeList = await (await fetch(`${CORE_URL}/internal/incomings?userId=${user}`)).json();
    assert.equal(activeList.length, 1);
    assert.equal(activeList[0].id, updated.id);

    // 5. SPOG must reflect £3500 and Day 28 (never £6500 or archived Day 25)
    const spogAfterEdit = await (await fetch(`${MONMON_URL}/api/spog/metrics?userId=${user}`)).json();
    assert.ok(
      Math.abs(spogAfterEdit.incomingTotal - 3500.0) < 0.05,
      `expected SPOG incomingTotal 3500.0, got ${spogAfterEdit.incomingTotal}`
    );
    assert.equal(spogAfterEdit.nextPayDay, 28);

    // 6. Superseded row must appear in /api/waste-bin
    const bin = await (await fetch(`${MONMON_URL}/api/waste-bin`, { headers: cookieHeader })).json();
    const archivedItem = (bin.items || []).find(i => i.entity_type === 'incoming' && i.id === created.id);
    assert.ok(archivedItem, 'archived original incoming must appear in /api/waste-bin');
    assert.equal(archivedItem.name, 'Old Salary');

    // 7. Stale-ID guard: editing the already-archived created.id must fail with 403 and NOT create a duplicate
    const staleRes = await fetch(`${CORE_URL}/internal/incomings/${created.id}`, {
      method: 'PUT',
      headers: { ...cookieHeader, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Stale Replay',
        netValue: 9999.0,
        incomingMonthDay: 10,
        userId: user,
        accountId: acc.id,
        payday: true
      })
    });
    assert.equal(staleRes.status, 403, 'editing an already-archived incoming must be rejected with 403');
    const afterStaleList = await (await fetch(`${CORE_URL}/internal/incomings?userId=${user}`)).json();
    assert.equal(afterStaleList.length, 1, 'stale edit must not spawn a duplicate active incoming');
    assert.equal(afterStaleList[0].id, updated.id);

    // 8. Cross-user ownership guard: another user cannot edit or archive the owner's incoming
    const attacker = `attacker_${Date.now()}`;
    const crossUserRes = await fetch(`${CORE_URL}/internal/incomings/${updated.id}`, {
      method: 'PUT',
      headers: {
        Cookie: `user_id=${attacker}; authenticated=true`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name: 'Hijacked Salary',
        netValue: 1.0,
        incomingMonthDay: 1,
        userId: attacker,
        accountId: acc.id,
        payday: true
      })
    });
    assert.equal(crossUserRes.status, 403, 'cross-user edit must be rejected with 403');
    const afterCrossList = await (await fetch(`${CORE_URL}/internal/incomings?userId=${user}`)).json();
    assert.equal(afterCrossList.length, 1);
    assert.equal(afterCrossList[0].id, updated.id);
    assert.equal(afterCrossList[0].name, 'Raised Salary');

    // 9. Edit via HTML form POST /updateIncoming/:id -> creates a 3rd distinct active row and shifts payday to Day 15
    const formBody = new URLSearchParams({
      name: 'Form Salary',
      netValue: '3750.00',
      incomingMonthDay: '15',
      account_id: String(acc.id),
      payday: 'true'
    });
    const formRes = await fetch(`${MONMON_URL}/updateIncoming/${updated.id}`, {
      method: 'POST',
      headers: {
        ...cookieHeader,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: formBody.toString(),
      redirect: 'manual'
    });
    assert.ok(
      formRes.status === 303 || formRes.status === 302,
      `expected redirect from POST /updateIncoming/:id, got ${formRes.status}`
    );

    const afterFormList = await (await fetch(`${CORE_URL}/internal/incomings?userId=${user}`)).json();
    assert.equal(afterFormList.length, 1);
    const third = afterFormList[0];
    assert.notEqual(third.id, updated.id, 'HTML form edit must also archive-and-recreate');
    assert.notEqual(third.id, created.id);
    assert.equal(third.name, 'Form Salary');
    assert.equal(third.netValue, 3750.0);
    assert.equal(third.incomingMonthDay, 15);

    const spogAfterForm = await (await fetch(`${MONMON_URL}/api/spog/metrics?userId=${user}`)).json();
    assert.ok(Math.abs(spogAfterForm.incomingTotal - 3750.0) < 0.05);
    assert.equal(spogAfterForm.nextPayDay, 15);
  });

  it('preserves historical income across Time Travel (monmon_time_travel) and /historicSpent cycles after salary raise', async () => {
    const user = `inc_tt_${Date.now()}`;
    const cookieHeader = { Cookie: `user_id=${user}; authenticated=true` };

    // 1. Create a DEBIT account + balances spanning 65 days ago -> today so /historicSpent has completed + current cycles
    const accRes = await fetch(`${CORE_URL}/internal/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'History Salary Account',
        type: 'DEBIT',
        userId: user,
        accountLimit: 0,
        interestRate: 0
      })
    });
    assert.equal(accRes.status, 200);
    const acc = await accRes.json();

    const nowSec = Math.floor(Date.now() / 1000);
    const daySec = 86400;
    for (const [offsetDays, value] of [
      [65, 2500.0],
      [40, 2100.0],
      [10, 3200.0],
      [0, 2900.0]
    ]) {
      const bRes = await fetch(`${CORE_URL}/internal/balances`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accountId: acc.id,
          userId: user,
          value,
          timestamp: nowSec - offsetDays * daySec
        })
      });
      assert.equal(bRes.status, 200);
    }

    // 2. Create initial salary (£2500, Day 1, payday = true) and backdate its created_at to 65 days ago + set type='SALARY'
    const createRes = await fetch(`${CORE_URL}/internal/incomings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Initial Job',
        netValue: 2500.0,
        grossValue: 3400.0,
        incomingMonthDay: 1,
        userId: user,
        accountId: acc.id,
        payday: true
      })
    });
    assert.equal(createRes.status, 200);
    const initial = await createRes.json();

    const backdatedMs = (nowSec - 65 * daySec) * 1000;
    runPsql(`UPDATE incoming SET created_at = ${backdatedMs}, updated_at = ${backdatedMs}, type = 'SALARY' WHERE id = ${initial.id};`);

    // 3. Edit salary today to £3200 ("Promoted Job") -> archives Initial Job at now_ms, creates Promoted Job at now_ms
    const editRes = await fetch(`${CORE_URL}/internal/incomings/${initial.id}`, {
      method: 'PUT',
      headers: { ...cookieHeader, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Promoted Job',
        netValue: 3200.0,
        incomingMonthDay: 1,
        userId: user,
        accountId: acc.id,
        payday: true
      })
    });
    assert.equal(editRes.status, 200, `PUT /internal/incomings/${initial.id} failed: ${editRes.status} ${await editRes.clone().text()}`);
    const promoted = await editRes.json();
    assert.notEqual(promoted.id, initial.id);
    assert.equal(promoted.incomingType, 'SALARY', 'type column must be preserved across archive-and-recreate');

    // Verify contiguous timestamp handoff in PostgreSQL: old.archived_at === new.created_at
    const tsCheck = runPsql(
      `SELECT (SELECT archived_at FROM incoming WHERE id = ${initial.id}) || '|' || (SELECT created_at FROM incoming WHERE id = ${promoted.id});`
    );
    const [oldArchivedAt, newCreatedAt] = tsCheck.split('|');
    assert.ok(oldArchivedAt && newCreatedAt, `expected non-empty timestamps, got ${tsCheck}`);
    assert.equal(oldArchivedAt, newCreatedAt, 'old.archived_at must equal new.created_at for contiguous history');

    // 4. Live /myIncomings shows Promoted Job (£3200) and NOT Initial Job
    const liveHtml = await (await fetch(`${MONMON_URL}/myIncomings`, { headers: cookieHeader })).text();
    assert.ok(liveHtml.includes('Promoted Job'), 'live /myIncomings must show Promoted Job');
    assert.ok(liveHtml.includes('3200'), 'live /myIncomings must show £3200');
    assert.ok(!liveHtml.includes('Initial Job'), 'live /myIncomings must not show archived Initial Job');

    // 5. Time Travel /myIncomings to 35 days ago (before edit) shows Initial Job (£2500) and NOT Promoted Job
    const ttDate = new Date((nowSec - 35 * daySec) * 1000).toISOString().slice(0, 10);
    const ttHtml = await (
      await fetch(`${MONMON_URL}/myIncomings`, {
        headers: { Cookie: `user_id=${user}; authenticated=true; monmon_time_travel=${ttDate}` }
      })
    ).text();
    assert.ok(ttHtml.includes('Initial Job'), `Time Travel (${ttDate}) /myIncomings must show Initial Job`);
    assert.ok(ttHtml.includes('2500'), `Time Travel (${ttDate}) /myIncomings must show £2500`);
    assert.ok(!ttHtml.includes('Promoted Job'), `Time Travel (${ttDate}) /myIncomings must not show future Promoted Job`);

    // 6. /historicSpent shows £2500.00 for previous completed cycle AND £3200.00 for current cycle (never £5700.00)
    const historicHtml = await (await fetch(`${MONMON_URL}/historicSpent`, { headers: cookieHeader })).text();
    assert.ok(historicHtml.includes('2500.00'), '/historicSpent must retain £2500.00 income for previous cycle');
    assert.ok(historicHtml.includes('3200.00'), '/historicSpent must show £3200.00 income for current cycle');
    assert.ok(!historicHtml.includes('5700.00'), '/historicSpent must never double-count old + new salary (£5700.00)');
  });
});
