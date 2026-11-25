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

const MONMON_JS_PATH = path.join(ROOT_DIR, 'modules/monmon-rs/public/javascripts/monthly-pot.js');
const PUBLIC_JS_PATH = path.join(ROOT_DIR, 'public/javascripts/monthly-pot.js');

function loadMonthlyPot(fakeDoc = null) {
  const src = fs.readFileSync(MONMON_JS_PATH, 'utf8');
  const document = fakeDoc || {
    getElementById: () => null,
    querySelectorAll: () => [],
    querySelector: () => null,
    body: { classList: { contains: () => false } },
  };
  const windowObj = { document };
  const context = vm.createContext({
    window: windowObj,
    document,
    console,
    setTimeout,
    clearTimeout,
    Event: class Event { constructor(type) { this.type = type; } },
  });
  vm.runInContext(src, context, { filename: 'monthly-pot.js' });
  return windowObj.MonMonMonthlyPot;
}

describe('monthly-pot.js calculateSectionsSummary', () => {
  it('sums categories under max monthly allowance', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary(
      [{ amount: 200 }, { amount: 50 }, { amount: 40 }],
      50000
    );
    assert.equal(res.plannedPence, 29000);
    assert.equal(res.maxAllowancePence, 50000);
    assert.equal(res.diffPence, 21000);
    assert.equal(res.isOver, false);
    assert.equal(res.absDiffPence, 21000);
    assert.equal(res.plannedFormatted, '290.00');
    assert.equal(res.maxFormatted, '500.00');
    assert.equal(res.absDiffFormatted, '210.00');
  });

  it('treats exact match as not over allowance with £0.00 remaining', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary([{ amount: 500 }], 50000);
    assert.equal(res.isOver, false);
    assert.equal(res.diffPence, 0);
    assert.equal(res.absDiffFormatted, '0.00');
  });

  it('flags over-allowance totals cleanly', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary([{ amount: 300 }, { amount: 250.5 }], 50000);
    assert.equal(res.plannedPence, 55050);
    assert.equal(res.isOver, true);
    assert.equal(res.diffPence, -5050);
    assert.equal(res.absDiffPence, 5050);
    assert.equal(res.plannedFormatted, '550.50');
    assert.equal(res.absDiffFormatted, '50.50');
  });

  it('handles zero or negative max monthly allowance (surplus <= 0)', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary([{ amount: 100 }], -5000);
    assert.equal(res.isOver, true);
    assert.equal(res.diffPence, -15000);
    assert.equal(res.absDiffPence, 15000);
    assert.equal(res.maxFormatted, '-50.00');
    assert.equal(res.absDiffFormatted, '150.00');
  });

  it('avoids floating-point drift on fractional pence amounts', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary(
      [{ amount: 0.1 }, { amount: 0.2 }, { amount: 216.67 }, { amount: 33.33 }],
      30000
    );
    assert.equal(res.plannedPence, 25030);
    assert.equal(res.plannedFormatted, '250.30');
    assert.equal(res.absDiffFormatted, '49.70');
  });

  it('clamps empty, invalid, null, and negative inputs to £0.00', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary(
      [{ amount: '' }, { amount: 'abc' }, { amount: -25 }, { amount: null }, { amount: 40.5 }],
      10000
    );
    assert.equal(res.plannedPence, 4050);
    assert.equal(res.isOver, false);
    assert.equal(res.absDiffFormatted, '59.50');
  });

  it('handles empty sections array cleanly', () => {
    const api = loadMonthlyPot();
    const res = api.calculateSectionsSummary([], 50000);
    assert.equal(res.plannedPence, 0);
    assert.equal(res.isOver, false);
    assert.equal(res.plannedFormatted, '0.00');
    assert.equal(res.absDiffFormatted, '500.00');
  });

  it('exports GRUELLING_TOTAL_PENCE equal to 21667', () => {
    const api = loadMonthlyPot();
    assert.equal(api.GRUELLING_TOTAL_PENCE, 21667);
  });

  it('mirrors monthly-pot.js identically between modules/monmon-rs/public and public', () => {
    const monmonSrc = fs.readFileSync(MONMON_JS_PATH, 'utf8');
    const publicSrc = fs.readFileSync(PUBLIC_JS_PATH, 'utf8');
    assert.equal(monmonSrc, publicSrc);
  });

  it('precaches monthly-pot in JS_PRECACHE_NAMES in both mirrored sw.js files', () => {
    const monmonSw = fs.readFileSync(
      path.join(ROOT_DIR, 'modules/monmon-rs/public/javascripts/sw.js'),
      'utf8'
    );
    const publicSw = fs.readFileSync(
      path.join(ROOT_DIR, 'public/javascripts/sw.js'),
      'utf8'
    );
    assert.equal(monmonSw, publicSw);
    assert.match(monmonSw, /'monthly-pot'/);
  });
});
