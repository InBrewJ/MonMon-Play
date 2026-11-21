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

function createClassList(initial = []) {
  const set = new Set(initial);
  return {
    add: (...cls) => cls.forEach((c) => set.add(c)),
    remove: (...cls) => cls.forEach((c) => set.delete(c)),
    contains: (c) => set.has(c),
  };
}

function createFakeEl(id, initial = {}) {
  const listeners = new Map();
  const attrs = new Map(Object.entries(initial.attrs || {}));
  let focused = false;
  return {
    id,
    value: initial.value ?? '',
    checked: Boolean(initial.checked),
    innerHTML: initial.innerHTML ?? '',
    textContent: initial.textContent ?? '',
    style: Object.assign({ display: initial.display ?? '' }, initial.style || {}),
    classList: createClassList(initial.classes || []),
    getAttribute: (name) => (attrs.has(name) ? attrs.get(name) : null),
    setAttribute: (name, val) => attrs.set(name, String(val)),
    addEventListener: (type, fn) => {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    dispatch: (type, ev = {}) => {
      const event = Object.assign({ stopPropagation: () => {} }, ev);
      for (const fn of listeners.get(type) || []) fn(event);
    },
    focus: () => { focused = true; },
    wasFocused: () => focused,
  };
}

function createMockCanvas() {
  const drawCalls = [];
  const ctx = {
    scale: () => {},
    clearRect: () => {},
    beginPath: () => {},
    moveTo: (x, y) => drawCalls.push({ op: 'moveTo', x, y }),
    lineTo: (x, y) => drawCalls.push({ op: 'lineTo', x, y }),
    stroke: () => drawCalls.push({ op: 'stroke' }),
    arc: (x, y, r) => drawCalls.push({ op: 'arc', x, y, r }),
    fill: () => drawCalls.push({ op: 'fill' }),
    fillText: (text, x, y) => drawCalls.push({ op: 'fillText', text, x, y }),
    setLineDash: () => {},
    strokeStyle: '',
    fillStyle: '',
    lineWidth: 1,
    lineJoin: 'miter',
    lineCap: 'butt',
    font: '',
    textAlign: 'left',
    textBaseline: 'alphabetic',
  };
  const canvas = createFakeEl('balance-chart');
  canvas.width = 0;
  canvas.height = 0;
  canvas.offsetWidth = 600;
  canvas.getContext = () => ctx;
  canvas.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600, height: 280 });
  canvas.parentElement = {
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 600, height: 280 }),
  };
  return { canvas, ctx, drawCalls };
}

function setupBalancesEnv({
  accounts = [
    { id: 101, name: 'Monzo Current' },
    { id: 102, name: 'Nationwide Bills' },
    { id: 5916, name: 'Barclaycard Platinum' },
    { id: 5917, name: 'Old ISA' },
    { id: 5918, name: 'Empty Account' },
  ],
  defaultAccountIds = [101, 102],
  preselectedAccountId = null,
  rawBalances = [],
  paydays = [],
} = {}) {
  const scriptPath = path.join(ROOT_DIR, 'public', 'javascripts', 'balances-chart.js');
  const code = fs.readFileSync(scriptPath, 'utf8');

  const { canvas, drawCalls } = createMockCanvas();
  const accSelect = createFakeEl('account', {
    value: preselectedAccountId ? String(preselectedAccountId) : String(accounts[0].id),
  });
  const valInput = createFakeEl('balance-value', { value: '' });
  const msBtn = createFakeEl('btn-account-multiselect');
  const msDropdown = createFakeEl('multiselect-dropdown', { display: 'none' });
  const msLabel = createFakeEl('multiselect-label', { textContent: 'Monthly Pot Accounts' });
  const potBtn = createFakeEl('select-pot-btn');
  const allBtn = createFakeEl('select-all-btn');
  const noneBtn = createFakeEl('select-none-btn');
  const emptyMsg = createFakeEl('empty-chart-msg', { display: 'none' });
  const tooltip = createFakeEl('chart-tooltip', { display: 'none' });
  const legendAccounts = createFakeEl('chart-legend-accounts');
  const rangeLabel = createFakeEl('chart-range-label', { textContent: 'Showing Last 2 Months' });
  const searchInput = createFakeEl('balance-search-input');
  const clearSearchBtn = createFakeEl('balance-search-clear', { display: 'none' });

  const dots = accounts.map((a) =>
    createFakeEl(`dot-${a.id}`, {
      attrs: { 'data-account-id': String(a.id), 'data-account-name': a.name },
    })
  );
  const checkboxes = accounts.map((a) =>
    createFakeEl(`chk-${a.id}`, { value: String(a.id), checked: false })
  );
  const timeButtons = [
    createFakeEl('range-2m', { attrs: { 'data-range': '2m' }, classes: ['btn-time-filter', 'active'] }),
    createFakeEl('range-6m', { attrs: { 'data-range': '6m' }, classes: ['btn-time-filter'] }),
    createFakeEl('range-1y', { attrs: { 'data-range': '1y' }, classes: ['btn-time-filter'] }),
    createFakeEl('range-all', { attrs: { 'data-range': 'all' }, classes: ['btn-time-filter'] }),
  ];

  const byId = new Map([
    ['balance-chart', canvas],
    ['account', accSelect],
    ['balance-value', valInput],
    ['btn-account-multiselect', msBtn],
    ['multiselect-dropdown', msDropdown],
    ['multiselect-label', msLabel],
    ['select-pot-btn', potBtn],
    ['select-all-btn', allBtn],
    ['select-none-btn', noneBtn],
    ['empty-chart-msg', emptyMsg],
    ['chart-tooltip', tooltip],
    ['chart-legend-accounts', legendAccounts],
    ['chart-range-label', rangeLabel],
    ['balance-search-input', searchInput],
    ['balance-search-clear', clearSearchBtn],
  ]);

  const selectorMap = new Map([
    ['.account-palette-dot', dots],
    ['.account-filter-chk', checkboxes],
    ['.btn-time-filter', timeButtons],
    ['.balance-data-row', []],
    ['.balance-mobile-card', []],
  ]);

  const timers = [];
  const docListeners = new Map();
  const winListeners = new Map();

  const sandbox = {
    rawBalances,
    paydays,
    defaultAccountIds,
    preselectedAccountId,
    devicePixelRatio: 1,
    document: {
      readyState: 'complete',
      getElementById: (id) => byId.get(id) || null,
      querySelectorAll: (sel) => selectorMap.get(sel) || [],
      addEventListener: (type, fn) => {
        if (!docListeners.has(type)) docListeners.set(type, []);
        docListeners.get(type).push(fn);
      },
    },
    addEventListener: (type, fn) => {
      if (!winListeners.has(type)) winListeners.set(type, []);
      winListeners.get(type).push(fn);
    },
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    Date,
    Math,
    Number,
    String,
    Set,
    Object,
    Array,
    Event: class Event { constructor(t) { this.type = t; } },
  };
  sandbox.window = sandbox;

  const ctx = vm.createContext(sandbox);
  vm.runInContext(code, ctx);
  for (const fn of timers) fn();

  return {
    sandbox,
    accSelect,
    valInput,
    msLabel,
    potBtn,
    allBtn,
    noneBtn,
    emptyMsg,
    legendAccounts,
    rangeLabel,
    checkboxes,
    timeButtons,
    drawCalls,
    getCheckedIds: () => checkboxes.filter((c) => c.checked).map((c) => c.value),
  };
}

describe('balances-chart.js selected account graph behavior', () => {
  const nowSec = Math.floor(Date.now() / 1000);
  const sampleBalances = [
    { id: 1, accountId: 101, accountName: 'Monzo Current', value: 1200, timestamp: nowSec - 5 * 86400, dateStr: '5d ago' },
    { id: 2, accountId: 102, accountName: 'Nationwide Bills', value: 800, timestamp: nowSec - 3 * 86400, dateStr: '3d ago' },
    { id: 3, accountId: 5916, accountName: 'Barclaycard Platinum', value: -350, timestamp: nowSec - 10 * 86400, dateStr: '10d ago' },
    { id: 4, accountId: 5916, accountName: 'Barclaycard Platinum', value: -420, timestamp: nowSec - 2 * 86400, dateStr: '2d ago' },
    { id: 5, accountId: 5917, accountName: 'Old ISA', value: 2500, timestamp: nowSec - 120 * 86400, dateStr: '120d ago' },
  ];

  it('shows only the preselected account on the graph for /myBalances?accountId=5916 without throwing', () => {
    const env = setupBalancesEnv({
      preselectedAccountId: 5916,
      rawBalances: sampleBalances,
    });

    assert.deepEqual(env.getCheckedIds(), ['5916']);
    assert.equal(env.msLabel.textContent, 'Barclaycard Platinum');
    assert.equal(env.emptyMsg.style.display, 'none');
    assert.match(env.legendAccounts.innerHTML, /Barclaycard Platinum/);
    assert.doesNotMatch(env.legendAccounts.innerHTML, /Monzo Current/);
    assert.equal(env.valInput.wasFocused(), true);

    // Negative balances must plot inside the canvas vertical plot area [25, 245]
    const arcs = env.drawCalls.filter((c) => c.op === 'arc');
    assert.equal(arcs.length, 2);
    for (const pt of arcs) {
      assert.ok(pt.y >= 25 && pt.y <= 245, `Expected plotted y (${pt.y}) within [25, 245]`);
    }
  });

  it('auto-expands the time range when the preselected account only has balances older than 2 months', () => {
    const env = setupBalancesEnv({
      preselectedAccountId: 5917,
      rawBalances: sampleBalances,
    });

    assert.deepEqual(env.getCheckedIds(), ['5917']);
    assert.equal(env.msLabel.textContent, 'Old ISA');
    assert.equal(env.emptyMsg.style.display, 'none');
    assert.match(env.legendAccounts.innerHTML, /Old ISA/);
    assert.equal(env.rangeLabel.textContent, 'Showing Last 6 Months');
    assert.equal(env.timeButtons.find((b) => b.getAttribute('data-range') === '6m')?.classList.contains('active'), true);
    assert.equal(env.timeButtons.find((b) => b.getAttribute('data-range') === '2m')?.classList.contains('active'), false);
  });

  it('defaults to Monthly Pot accounts on /myBalances and switches the graph when an account is selected in #account', () => {
    const env = setupBalancesEnv({
      preselectedAccountId: null,
      rawBalances: sampleBalances,
    });

    // Initial state: Monthly Pot accounts (101, 102)
    assert.deepEqual(env.getCheckedIds(), ['101', '102']);
    assert.match(env.legendAccounts.innerHTML, /Monzo Current/);
    assert.match(env.legendAccounts.innerHTML, /Nationwide Bills/);
    assert.doesNotMatch(env.legendAccounts.innerHTML, /Barclaycard Platinum/);

    // User selects account 5916 from the "Which Account?" dropdown
    env.accSelect.value = '5916';
    env.accSelect.dispatch('change');

    assert.deepEqual(env.getCheckedIds(), ['5916']);
    assert.equal(env.msLabel.textContent, 'Barclaycard Platinum');
    assert.equal(env.emptyMsg.style.display, 'none');
    assert.match(env.legendAccounts.innerHTML, /Barclaycard Platinum/);
    assert.doesNotMatch(env.legendAccounts.innerHTML, /Monzo Current/);

    // User selects an account whose only balance is older than 2M -> auto-expands range
    env.accSelect.value = '5917';
    env.accSelect.dispatch('change');

    assert.deepEqual(env.getCheckedIds(), ['5917']);
    assert.equal(env.msLabel.textContent, 'Old ISA');
    assert.equal(env.emptyMsg.style.display, 'none');
    assert.match(env.legendAccounts.innerHTML, /Old ISA/);
    assert.equal(env.rangeLabel.textContent, 'Showing Last 6 Months');

    // User selects an account with no recorded balances -> shows empty state and clears stale legend
    env.accSelect.value = '5918';
    env.accSelect.dispatch('change');

    assert.deepEqual(env.getCheckedIds(), ['5918']);
    assert.equal(env.emptyMsg.style.display, 'flex');
    assert.doesNotMatch(env.legendAccounts.innerHTML, /Old ISA/);

    // Quick-select "Monthly Pot" button still restores Monthly Pot accounts
    env.potBtn.dispatch('click');
    assert.deepEqual(env.getCheckedIds(), ['101', '102']);
    assert.equal(env.emptyMsg.style.display, 'none');
    assert.match(env.legendAccounts.innerHTML, /Monzo Current/);
  });

  it('auto-expands to All History when selected account only has balances older than 1 year', () => {
    const oldOnlyBalances = [
      ...sampleBalances,
      { id: 6, accountId: 5918, accountName: 'Empty Account', value: 500, timestamp: nowSec - 500 * 86400, dateStr: '500d ago' },
    ];
    const env = setupBalancesEnv({
      preselectedAccountId: 5918,
      rawBalances: oldOnlyBalances,
    });

    assert.deepEqual(env.getCheckedIds(), ['5918']);
    assert.equal(env.emptyMsg.style.display, 'none');
    assert.equal(env.rangeLabel.textContent, 'Showing All History');
    assert.equal(env.timeButtons.find((b) => b.getAttribute('data-range') === 'all')?.classList.contains('active'), true);
  });

  function extractLegendHues(html) {
    const hues = [];
    const re = /background:\s*hsl\((\d+(?:\.\d+)?),/g;
    let m;
    while ((m = re.exec(html)) !== null) {
      hues.push(Number(m[1]));
    }
    return hues;
  }

  function minPairwiseHueDist(hues) {
    let minD = 360;
    for (let i = 0; i < hues.length; i++) {
      for (let j = i + 1; j < hues.length; j++) {
        const diff = Math.abs(hues[i] - hues[j]) % 360;
        const d = diff > 180 ? 360 - diff : diff;
        if (d < minD) minD = d;
      }
    }
    return minD;
  }

  it('spaces visible line colours maximally far apart on the colour wheel (fewer lines = wider hue separation)', () => {
    const manyAccounts = [
      { id: 1, name: 'CASH MONEY' },
      { id: 2, name: 'Monzo' },
      { id: 3, name: 'Natwest Reward Debit' },
      { id: 4, name: 'Barclaycard' },
      { id: 5, name: 'Savings Pot' },
      { id: 6, name: 'ISA' },
      { id: 7, name: 'Joint Account' },
      { id: 8, name: 'Holiday Fund' },
    ];
    const manyBalances = manyAccounts.map((a, idx) => ({
      id: idx + 1,
      accountId: a.id,
      accountName: a.name,
      value: 100 * (idx + 1),
      timestamp: nowSec - (idx + 1) * 86400,
      dateStr: `${idx + 1}d ago`,
    }));

    // 2 accounts (CASH MONEY & Monzo) -> near-complementary (>= 150 deg apart)
    const env2 = setupBalancesEnv({
      accounts: manyAccounts,
      defaultAccountIds: [1, 2],
      rawBalances: manyBalances,
    });
    const hues2 = extractLegendHues(env2.legendAccounts.innerHTML);
    assert.equal(hues2.length, 2);
    const dist2 = minPairwiseHueDist(hues2);
    assert.ok(dist2 >= 150, `Expected 2 lines to be >= 150° apart, got ${dist2}° (${hues2.join(', ')})`);
    for (const h of hues2) {
      assert.ok(h < 144 || h > 176, `Expected hue ${h}° to avoid Payday green [144, 176]`);
    }

    // 3 accounts -> triadic (>= 100 deg apart)
    const env3 = setupBalancesEnv({
      accounts: manyAccounts,
      defaultAccountIds: [1, 2, 3],
      rawBalances: manyBalances,
    });
    const hues3 = extractLegendHues(env3.legendAccounts.innerHTML);
    assert.equal(hues3.length, 3);
    const dist3 = minPairwiseHueDist(hues3);
    assert.ok(dist3 >= 100, `Expected 3 lines to be >= 100° apart, got ${dist3}° (${hues3.join(', ')})`);

    // 4 accounts -> tetradic (>= 75 deg apart)
    const env4 = setupBalancesEnv({
      accounts: manyAccounts,
      defaultAccountIds: [1, 2, 3, 4],
      rawBalances: manyBalances,
    });
    const hues4 = extractLegendHues(env4.legendAccounts.innerHTML);
    assert.equal(hues4.length, 4);
    const dist4 = minPairwiseHueDist(hues4);
    assert.ok(dist4 >= 75, `Expected 4 lines to be >= 75° apart, got ${dist4}° (${hues4.join(', ')})`);

    // 8 accounts -> evenly distributed (>= 38 deg apart)
    const env8 = setupBalancesEnv({
      accounts: manyAccounts,
      defaultAccountIds: [1, 2, 3, 4, 5, 6, 7, 8],
      rawBalances: manyBalances,
    });
    const hues8 = extractLegendHues(env8.legendAccounts.innerHTML);
    assert.equal(hues8.length, 8);
    const dist8 = minPairwiseHueDist(hues8);
    assert.ok(dist8 >= 38, `Expected 8 lines to be >= 38° apart, got ${dist8}° (${hues8.join(', ')})`);

    // Fewer lines => strictly wider minimum hue separation
    assert.ok(dist2 > dist3 && dist3 > dist4 && dist4 > dist8,
      `Expected wider hue spacing with fewer lines: 2=${dist2}°, 3=${dist3}°, 4=${dist4}°, 8=${dist8}°`);
  });
});
