// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ROOT_DIR = path.resolve(__dirname, '../..');

function createFakeEl(id, initial = {}) {
  const listeners = new Map();
  return {
    id,
    value: initial.value ?? '',
    innerHTML: '',
    textContent: '',
    style: { display: initial.display ?? '' },
    addEventListener: (type, fn) => {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    dispatch: (type, ev = {}) => {
      for (const fn of listeners.get(type) || []) fn(ev);
    },
    focus: () => {},
  };
}

function setupEnv(fetchData = []) {
  const code = fs.readFileSync(path.join(ROOT_DIR, 'public', 'javascripts', 'erratic-balances.js'), 'utf8');
  const modal = createFakeEl('erratic-balances-modal', { display: 'none' });
  const container = createFakeEl('erratic-balances-container');
  const countLbl = createFakeEl('erratic-result-count');
  const thresholdInput = createFakeEl('erratic-threshold-input', { value: '500' });
  const searchInput = createFakeEl('erratic-account-search', { value: '' });
  const clearBtn = createFakeEl('erratic-search-clear', { display: 'none' });

  const byId = new Map([
    ['erratic-balances-modal', modal],
    ['erratic-balances-container', container],
    ['erratic-result-count', countLbl],
    ['erratic-threshold-input', thresholdInput],
    ['erratic-account-search', searchInput],
    ['erratic-search-clear', clearBtn],
  ]);

  const fetchCalls = [];
  let pendingResponse = fetchData;

  const sandbox = {
    document: {
      readyState: 'complete',
      getElementById: (id) => byId.get(id) || null,
      addEventListener: () => {},
    },
    fetch: (url) => {
      fetchCalls.push(url);
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(pendingResponse),
      });
    },
    encodeURIComponent,
    Date,
    Number,
    String,
    Math,
  };
  sandbox.window = sandbox;

  const ctx = vm.createContext(sandbox);
  vm.runInContext(code, ctx);

  return {
    sandbox,
    modal,
    container,
    countLbl,
    thresholdInput,
    searchInput,
    clearBtn,
    fetchCalls,
    setFetchData: (d) => { pendingResponse = d; },
  };
}

describe('erratic-balances.js inspector date display & account search', () => {
  const sampleData = [
    {
      accountId: 1,
      accountName: 'Monzo Current',
      prevBalanceId: 10,
      currBalanceId: 11,
      prevValue: 1000.0,
      currValue: 250.5,
      jumpAmount: 749.5,
      timestamp: Math.floor(Date.UTC(2026, 10, 21, 14, 32) / 1000), // 2026-11-21 14:32:00 UTC (epoch seconds)
    },
    {
      accountId: 2,
      accountName: 'Natwest <Reward> Debit',
      prevBalanceId: 20,
      currBalanceId: 21,
      prevValue: 3200.0,
      currValue: 200.0,
      jumpAmount: 3000.0,
      timestamp: Date.UTC(2026, 9, 31, 0, 0), // 2026-10-31 00:00:00 UTC (epoch milliseconds)
    },
  ];

  it('renders formatted transaction dates (both seconds & ms), 2dp values, and escaped account names', async () => {
    const env = setupEnv(sampleData);
    env.sandbox.openErraticBalancesModal();
    await new Promise((r) => setTimeout(r, 0));

    assert.equal(env.modal.style.display, 'block');
    assert.equal(env.countLbl.textContent, 'Found 2 jump(s)');
    assert.match(env.container.innerHTML, /21 Nov 2026/);
    assert.match(env.container.innerHTML, /31 Oct 2026/);
    assert.match(env.container.innerHTML, /£1000\.00/);
    assert.match(env.container.innerHTML, /£250\.50/);
    assert.match(env.container.innerHTML, /£749\.50/);
    assert.match(env.container.innerHTML, /Natwest &lt;Reward&gt; Debit/);
    assert.doesNotMatch(env.container.innerHTML, /Natwest <Reward> Debit/);
  });

  it('filters jumps live by account name (case-insensitive) and updates count & clear button', async () => {
    const env = setupEnv(sampleData);
    env.sandbox.loadErraticBalances();
    await new Promise((r) => setTimeout(r, 0));

    env.searchInput.value = 'monzo';
    env.searchInput.dispatch('input');

    assert.equal(env.countLbl.textContent, 'Showing 1 of 2 jump(s)');
    assert.equal(env.clearBtn.style.display, 'inline-flex');
    assert.match(env.container.innerHTML, /Monzo Current/);
    assert.doesNotMatch(env.container.innerHTML, /Natwest/);

    // No match state
    env.searchInput.value = 'barclays';
    env.searchInput.dispatch('input');
    assert.equal(env.countLbl.textContent, 'Showing 0 of 2 jump(s)');
    assert.match(env.container.innerHTML, /No erratic jumps matching/);

    // Clear resets filter
    env.clearBtn.dispatch('click');
    assert.equal(env.searchInput.value, '');
    assert.equal(env.clearBtn.style.display, 'none');
    assert.equal(env.countLbl.textContent, 'Found 2 jump(s)');
  });

  it('preserves active account search filter when re-running threshold check', async () => {
    const env = setupEnv(sampleData);
    env.searchInput.value = 'natwest';
    env.thresholdInput.value = '1000';
    env.sandbox.loadErraticBalances();
    await new Promise((r) => setTimeout(r, 0));

    assert.equal(env.fetchCalls[0], '/api/balances/erratic?threshold=1000');
    assert.equal(env.countLbl.textContent, 'Showing 1 of 2 jump(s)');
    assert.match(env.container.innerHTML, /Natwest/);
    assert.doesNotMatch(env.container.innerHTML, /Monzo Current/);
  });
});
