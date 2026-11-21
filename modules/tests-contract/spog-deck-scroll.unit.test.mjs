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
    toggle: (c, force) => {
      if (force === undefined) {
        if (set.has(c)) { set.delete(c); return false; }
        set.add(c); return true;
      }
      if (force) set.add(c); else set.delete(c);
      return Boolean(force);
    },
  };
}

function createFakeElement(id, opts = {}) {
  const listeners = new Map();
  const attrs = new Map(Object.entries(opts.attrs || {}));
  let rectLeft = opts.rectLeft ?? 0;
  let rectWidth = opts.rectWidth ?? 375;
  let offsetHeight = opts.offsetHeight ?? 600;
  const styleProps = new Map();
  const scrollToCalls = [];
  let scrollIntoViewCalls = 0;

  const el = {
    id,
    classList: createClassList(opts.classes || []),
    style: new Proxy({}, {
      get(_t, prop) { return styleProps.get(prop) ?? ''; },
      set(_t, prop, val) {
        styleProps.set(prop, val);
        if (opts.onStyleSet) opts.onStyleSet(String(prop), val);
        return true;
      },
    }),
    scrollLeft: 0,
    scrollWidth: opts.scrollWidth ?? 750,
    clientWidth: opts.clientWidth ?? 375,
    offsetTop: opts.offsetTop ?? 0,
    offsetParent: null,
    get offsetHeight() { return offsetHeight; },
    set offsetHeight(v) { offsetHeight = v; },
    getAttribute: (name) => (attrs.has(name) ? attrs.get(name) : null),
    setAttribute: (name, val) => attrs.set(name, String(val)),
    addEventListener: (type, fn, options) => {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push({ fn, options });
    },
    removeEventListener: (type, fn) => {
      const list = listeners.get(type) || [];
      listeners.set(type, list.filter((entry) => entry.fn !== fn));
    },
    dispatch: (type, event = {}) => {
      const list = listeners.get(type) || [];
      for (const entry of list) entry.fn(event);
    },
    getBoundingClientRect: () => ({ left: rectLeft, width: rectWidth, top: 0, height: offsetHeight }),
    setRectLeft: (v) => { rectLeft = v; },
    scrollTo: (arg) => { scrollToCalls.push(arg); },
    scrollIntoView: () => { scrollIntoViewCalls++; },
    querySelectorAll: (sel) => (opts.querySelectorAll ? opts.querySelectorAll(sel) : []),
    querySelector: (sel) => (opts.querySelector ? opts.querySelector(sel) : null),
    closest: () => null,
    _scrollToCalls: scrollToCalls,
    _getScrollIntoViewCalls: () => scrollIntoViewCalls,
    _listeners: listeners,
  };
  return el;
}

describe('spog-deck.js iOS vertical scroll safety', () => {
  function setupDeckEnv() {
    const timers = [];
    const styleWrites = [];
    const cssVarWrites = [];
    const windowScrollCalls = [];
    const windowListeners = new Map();

    const card1 = createFakeElement('spog-card-pot', {
      rectLeft: 0,
      rectWidth: 375,
      offsetHeight: 700,
      onStyleSet: (prop, val) => styleWrites.push({ card: 'pot', prop, val }),
    });
    const card2 = createFakeElement('spog-card-spent', {
      rectLeft: 391,
      rectWidth: 375,
      offsetHeight: 900,
      onStyleSet: (prop, val) => styleWrites.push({ card: 'spent', prop, val }),
    });

    const pill1 = createFakeElement('pill-1', {
      classes: ['spog-nav-pill', 'active'],
      attrs: { 'data-target': 'spog-card-pot' },
      rectLeft: 10,
      rectWidth: 100,
    });
    const pill2 = createFakeElement('pill-2', {
      classes: ['spog-nav-pill'],
      attrs: { 'data-target': 'spog-card-spent' },
      rectLeft: 420,
      rectWidth: 100,
    });

    const nav = createFakeElement('spog-nav', {
      rectLeft: 0,
      rectWidth: 375,
      clientWidth: 375,
      scrollWidth: 600,
      offsetTop: 80,
      querySelectorAll: () => [pill1, pill2],
      querySelector: () => pill1,
    });

    const grid = createFakeElement('spog-grid', {
      rectLeft: 0,
      rectWidth: 375,
      querySelectorAll: () => [card1, card2],
    });

    const header = createFakeElement('app-header', { offsetHeight: 50 });
    const mainNav = createFakeElement('main-nav', { offsetHeight: 44 });

    const byId = {
      'spog-nav': nav,
      'spog-card-pot': card1,
      'spog-card-spent': card2,
      'main-nav': mainNav,
    };

    const document = {
      readyState: 'complete',
      documentElement: {
        scrollTop: 300,
        style: {
          setProperty: (k, v) => cssVarWrites.push({ k, v }),
        },
      },
      getElementById: (id) => byId[id] || null,
      querySelector: (sel) => {
        if (sel === '.spog-layout-grid') return grid;
        if (sel === 'header.app-header') return header;
        if (sel === 'nav.crumbs') return mainNav;
        return null;
      },
      querySelectorAll: (sel) => {
        if (sel === '.deck-nav') return [];
        return [];
      },
      addEventListener: () => {},
    };

    const windowObj = {
      innerWidth: 375,
      innerHeight: 667,
      pageYOffset: 300,
      location: { hash: '' },
      document,
      requestAnimationFrame: (fn) => { fn(); return 1; },
      scrollTo: (arg) => windowScrollCalls.push(arg),
      addEventListener: (type, fn, opts) => {
        if (!windowListeners.has(type)) windowListeners.set(type, []);
        windowListeners.get(type).push({ fn, opts });
      },
      dispatch: (type, ev = {}) => {
        for (const entry of windowListeners.get(type) || []) entry.fn(ev);
      },
    };

    const sandbox = {
      window: windowObj,
      document,
      setTimeout: (fn, ms) => {
        const id = timers.length + 1;
        timers.push({ id, fn, ms, cleared: false });
        return id;
      },
      clearTimeout: (id) => {
        const t = timers.find((x) => x.id === id);
        if (t) t.cleared = true;
      },
    };

    const src = fs.readFileSync(path.join(ROOT_DIR, 'public/javascripts/spog-deck.js'), 'utf8');
    vm.runInNewContext(src, sandbox);

    return {
      windowObj, nav, grid, pill1, pill2, card1, card2,
      timers, styleWrites, cssVarWrites, windowScrollCalls,
      flushTimers: () => {
        for (const t of timers.slice()) {
          if (!t.cleared) {
            t.cleared = true;
            t.fn();
          }
        }
      },
    };
  }

  it('pure vertical touch gesture on deck grid does not trigger scrollIntoView, nav scroll, window scroll, or height writes', () => {
    const env = setupDeckEnv();
    env.styleWrites.length = 0;
    env.cssVarWrites.length = 0;
    env.windowScrollCalls.length = 0;
    env.nav._scrollToCalls.length = 0;

    // Simulate a vertical flick on card1 inside grid: scrollLeft never changes
    env.grid.dispatch('touchstart');
    env.grid.dispatch('touchend');
    env.flushTimers();

    assert.equal(env.pill1._getScrollIntoViewCalls(), 0, 'pill.scrollIntoView must never be called');
    assert.equal(env.pill2._getScrollIntoViewCalls(), 0, 'pill.scrollIntoView must never be called');
    assert.equal(env.card1._getScrollIntoViewCalls(), 0, 'card.scrollIntoView must never be called');
    assert.equal(env.nav._scrollToCalls.length, 0, 'nav.scrollTo must not run on pure vertical touch');
    assert.equal(env.windowScrollCalls.length, 0, 'window.scrollTo must not run on pure vertical touch');
    assert.equal(env.styleWrites.length, 0, 'card styles must not mutate on pure vertical touch');
  });

  it('iOS dynamic vertical viewport resize (innerHeight changes, innerWidth unchanged) is ignored', () => {
    const env = setupDeckEnv();
    env.styleWrites.length = 0;
    env.cssVarWrites.length = 0;

    // Simulate iOS Safari collapsing its address bar while scrolling down
    env.windowObj.innerHeight = 760;
    env.windowObj.dispatch('resize');

    assert.equal(env.cssVarWrites.length, 0, 'CSS vars must not be rewritten on vertical-only resize');
    assert.equal(env.styleWrites.length, 0, 'card heights must not be mutated on vertical-only resize');
  });

  it('horizontal swipe settles cleanly using nav.scrollTo without calling scrollIntoView', () => {
    const env = setupDeckEnv();
    env.nav._scrollToCalls.length = 0;
    env.windowScrollCalls.length = 0;

    // Simulate swiping horizontally to card2
    env.grid.dispatch('touchstart');
    env.grid.scrollLeft = 391;
    env.card1.setRectLeft(-391);
    env.card2.setRectLeft(0);
    env.grid.dispatch('scroll');
    env.grid.dispatch('touchend');
    env.flushTimers();

    assert.equal(env.pill2.classList.contains('active'), true, 'pill2 should become active');
    assert.equal(env.pill2._getScrollIntoViewCalls(), 0, 'must use nav.scrollTo instead of pill.scrollIntoView');
    assert.equal(env.nav._scrollToCalls.length, 1, 'nav.scrollTo should center active pill horizontally');
    assert.equal(env.windowScrollCalls.length, 1, 'changing active card while scrolled down should snap to top');
  });

  it('clicking a pill scrolls grid via grid.scrollTo rather than card.scrollIntoView', () => {
    const env = setupDeckEnv();
    env.grid._scrollToCalls.length = 0;

    env.pill2.dispatch('click', { preventDefault: () => {} });

    assert.equal(env.card2._getScrollIntoViewCalls(), 0, 'must not call card.scrollIntoView');
    assert.equal(env.pill2._getScrollIntoViewCalls(), 0, 'must not call pill.scrollIntoView');
    assert.equal(env.grid._scrollToCalls.length, 1, 'should call grid.scrollTo horizontally');
    assert.equal(env.grid._scrollToCalls[0].left, 391);
  });
});

describe('modal-scroll-lock.js passive scroll hygiene', () => {
  it('registers zero non-passive touchmove/wheel listeners while no modal is open, and attaches/detaches on lock/unlock', () => {
    const activeListeners = [];
    const docEl = { classList: createClassList(), clientWidth: 375, scrollTop: 0 };
    const body = { classList: createClassList(), style: {} };
    let domContentLoadedFn = null;

    const windowObj = {
      innerWidth: 375,
      scrollY: 0,
      pageYOffset: 0,
      scrollTo: () => {},
      getComputedStyle: () => ({ display: 'none', visibility: 'hidden', opacity: '0', overflowY: 'visible' }),
      addEventListener: (type, fn, opts) => {
        activeListeners.push({ type, fn, opts });
      },
      removeEventListener: (type, fn) => {
        for (let i = activeListeners.length - 1; i >= 0; i--) {
          if (activeListeners[i].type === type && activeListeners[i].fn === fn) {
            activeListeners.splice(i, 1);
          }
        }
      },
    };

    const document = {
      documentElement: docEl,
      body,
      querySelectorAll: () => [],
      addEventListener: (type, fn) => {
        if (type === 'DOMContentLoaded') domContentLoadedFn = fn;
      },
    };

    const sandbox = {
      window: windowObj,
      document,
      MutationObserver: class { observe() {} },
    };

    const src = fs.readFileSync(path.join(ROOT_DIR, 'public/javascripts/modal-scroll-lock.js'), 'utf8');
    vm.runInNewContext(src, sandbox);
    domContentLoadedFn();

    // No modal open => ZERO touchmove/wheel listeners blocking Safari's compositor scroll thread
    assert.equal(
      activeListeners.filter((l) => l.type === 'touchmove' || l.type === 'wheel').length,
      0,
      'must not attach touchmove/wheel listeners when no modal is open'
    );

    // Opening a modal attaches them
    windowObj.lockModalScroll();
    assert.equal(activeListeners.filter((l) => l.type === 'touchmove').length, 1);
    assert.equal(activeListeners.filter((l) => l.type === 'wheel').length, 1);

    // Closing a modal removes them cleanly
    windowObj.unlockModalScroll();
    assert.equal(activeListeners.filter((l) => l.type === 'touchmove' || l.type === 'wheel').length, 0);
  });
});
