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

function createClassList(initial = [], onMutate = null) {
  const set = new Set(initial);
  const notify = () => { if (onMutate) onMutate(); };
  return {
    add: (...cls) => { cls.forEach((c) => set.add(c)); notify(); },
    remove: (...cls) => { cls.forEach((c) => set.delete(c)); notify(); },
    contains: (c) => set.has(c),
    toggle: (c, force) => {
      const result = force === undefined ? !set.has(c) : Boolean(force);
      if (result) set.add(c); else set.delete(c);
      notify();
      return result;
    },
  };
}

function createFakeEl(id, opts = {}) {
  const listeners = new Map();
  const attrs = new Map(Object.entries(opts.attrs || {}));
  const observers = [];
  const el = {
    id,
    classList: createClassList(opts.classes || [], () => {
      for (const cb of observers) cb([{ type: 'attributes', attributeName: 'class', target: el }]);
    }),
    getAttribute: (k) => (attrs.has(k) ? attrs.get(k) : null),
    setAttribute: (k, v) => attrs.set(k, String(v)),
    addEventListener: (type, fn) => {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    dispatch: (type, event = {}) => {
      const fns = listeners.get(type) || [];
      for (const fn of fns) fn(event);
    },
    scrollLeft: 0,
    scrollTo: (arg) => {
      el._scrollToCalls.push(arg);
      if (arg && typeof arg.left === 'number') el.scrollLeft = arg.left;
    },
    getBoundingClientRect: () => ({ left: opts.rectLeft ?? 0, width: opts.rectWidth ?? 375 }),
    querySelectorAll: (sel) => (opts.querySelectorAll ? opts.querySelectorAll(sel) : []),
    _scrollToCalls: [],
    _registerObserver: (cb) => observers.push(cb),
  };
  return el;
}

function setupEnv({ initialHash = '', innerWidth = 1280 } = {}) {
  const scriptPath = path.join(ROOT_DIR, 'public', 'javascripts', 'historic-spent.js');
  const code = fs.readFileSync(scriptPath, 'utf8');

  const pillPots = createFakeEl('pill-pots', {
    classes: ['deck-nav-pill', 'active'],
    attrs: { 'data-target': 'historic-card-pots', 'data-hash': 'pots', 'aria-selected': 'true' },
  });
  const pillAll = createFakeEl('pill-all', {
    classes: ['deck-nav-pill'],
    attrs: { 'data-target': 'historic-card-all-accounts', 'data-hash': 'all-accounts', 'aria-selected': 'false' },
  });
  const cardPots = createFakeEl('historic-card-pots', {
    classes: ['deck-card', 'historic-tab-active'],
    rectLeft: 0,
    rectWidth: 375,
  });
  const cardAll = createFakeEl('historic-card-all-accounts', {
    classes: ['deck-card'],
    rectLeft: 391,
    rectWidth: 375,
  });
  const paceLink = createFakeEl('pace-link-0', { classes: ['historic-pace-link'] });

  const nav = createFakeEl('historic-spent-nav', {
    querySelectorAll: (sel) => (sel.includes('.deck-nav-pill') ? [pillPots, pillAll] : []),
  });
  const grid = createFakeEl('historic-spent-grid', {
    rectLeft: 0,
    rectWidth: 375,
    querySelectorAll: (sel) => (sel.includes('.deck-card') ? [cardPots, cardAll] : []),
  });

  const byId = new Map([
    ['historic-spent-nav', nav],
    ['historic-spent-grid', grid],
    ['historic-card-pots', cardPots],
    ['historic-card-all-accounts', cardAll],
  ]);

  const windowListeners = new Map();
  const docListeners = new Map();
  const replaceStateCalls = [];
  const locationObj = {
    hash: initialHash,
    pathname: '/historicSpent',
    search: '',
  };

  class FakeMutationObserver {
    constructor(cb) { this.cb = cb; }
    observe(target) {
      if (target && typeof target._registerObserver === 'function') {
        target._registerObserver(this.cb);
      }
    }
    disconnect() {}
  }

  const sandbox = {
    window: null,
    document: {
      readyState: 'complete',
      getElementById: (id) => byId.get(id) || null,
      querySelectorAll: (sel) => (sel.includes('.historic-pace-link') ? [paceLink] : []),
      addEventListener: (type, fn) => {
        if (!docListeners.has(type)) docListeners.set(type, []);
        docListeners.get(type).push(fn);
      },
    },
    MutationObserver: FakeMutationObserver,
    history: {
      replaceState: (_state, _title, url) => {
        replaceStateCalls.push(url);
        const hashIdx = String(url).indexOf('#');
        locationObj.hash = hashIdx >= 0 ? String(url).slice(hashIdx) : '';
      },
    },
    location: locationObj,
    setTimeout: (fn) => { fn(); return 1; },
    clearTimeout: () => {},
  };

  sandbox.window = {
    innerWidth,
    location: locationObj,
    history: sandbox.history,
    addEventListener: (type, fn) => {
      if (!windowListeners.has(type)) windowListeners.set(type, []);
      windowListeners.get(type).push(fn);
    },
    dispatch: (type, event = {}) => {
      const fns = windowListeners.get(type) || [];
      for (const fn of fns) fn(event);
    },
  };

  vm.runInNewContext(code, sandbox);

  return {
    pillPots,
    pillAll,
    cardPots,
    cardAll,
    paceLink,
    nav,
    grid,
    locationObj,
    replaceStateCalls,
    window: sandbox.window,
  };
}

describe('historic-spent.js view switcher & hash sync', () => {
  it('defaults to Monthly Pots (#pots) when URL hash is empty or unknown', () => {
    const env = setupEnv({ initialHash: '', innerWidth: 1280 });
    assert.equal(env.cardPots.classList.contains('historic-tab-active'), true);
    assert.equal(env.cardAll.classList.contains('historic-tab-active'), false);
    assert.equal(env.pillPots.classList.contains('active'), true);
    assert.equal(env.pillAll.classList.contains('active'), false);

    const envUnknown = setupEnv({ initialHash: '#something-else', innerWidth: 1280 });
    assert.equal(envUnknown.cardPots.classList.contains('historic-tab-active'), true);
    assert.equal(envUnknown.cardAll.classList.contains('historic-tab-active'), false);
  });

  it('initializes directly to All Accounts when hash is #all-accounts', () => {
    const env = setupEnv({ initialHash: '#all-accounts', innerWidth: 1280 });
    assert.equal(env.cardPots.classList.contains('historic-tab-active'), false);
    assert.equal(env.cardAll.classList.contains('historic-tab-active'), true);
    assert.equal(env.pillAll.classList.contains('active'), true);
    assert.equal(env.pillAll.getAttribute('aria-selected'), 'true');
    assert.equal(env.pillPots.getAttribute('aria-selected'), 'false');
  });

  it('switches active card and updates URL hash on desktop pill click', () => {
    const env = setupEnv({ initialHash: '#pots', innerWidth: 1280 });
    let prevented = false;
    env.pillAll.dispatch('click', { preventDefault: () => { prevented = true; } });

    assert.equal(prevented, true);
    assert.equal(env.cardAll.classList.contains('historic-tab-active'), true);
    assert.equal(env.cardPots.classList.contains('historic-tab-active'), false);
    assert.equal(env.pillAll.classList.contains('active'), true);
    assert.equal(env.pillPots.classList.contains('active'), false);
    assert.equal(env.locationObj.hash, '#all-accounts');
  });

  it('responds to hashchange back/forward navigation', () => {
    const env = setupEnv({ initialHash: '#all-accounts', innerWidth: 1280 });
    assert.equal(env.cardAll.classList.contains('historic-tab-active'), true);

    env.locationObj.hash = '#pots';
    env.window.dispatch('hashchange');
    assert.equal(env.cardPots.classList.contains('historic-tab-active'), true);
    assert.equal(env.cardAll.classList.contains('historic-tab-active'), false);
  });

  it('stops click propagation on .historic-pace-link inside <summary>', () => {
    const env = setupEnv({ initialHash: '#pots', innerWidth: 1280 });
    let stopped = false;
    env.paceLink.dispatch('click', { stopPropagation: () => { stopped = true; } });
    assert.equal(stopped, true);
  });

  it('syncs URL hash when spog-deck updates active pill after mobile swipe', () => {
    const env = setupEnv({ initialHash: '#pots', innerWidth: 375 });
    // Simulate spog-deck toggling .active onto the second pill after swipe settle
    env.pillPots.classList.remove('active');
    env.pillAll.classList.add('active');

    assert.equal(env.locationObj.hash, '#all-accounts');
    assert.equal(env.cardAll.classList.contains('historic-tab-active'), true);
  });
});
