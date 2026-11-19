// @ts-check
import { describe, it, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ROOT_DIR = path.resolve(__dirname, '../..');
const OFFLINE_MODE_PATH = path.join(ROOT_DIR, 'public/javascripts/offline-mode.js');
const ORIGIN = 'https://monmon.test';

function createFakeTimers(initialNow = 1_760_000_000_000) {
  let now = initialNow;
  let nextId = 1;
  const timers = new Map();

  function setTimer(fn, delay = 0) {
    const id = nextId++;
    const ms = Math.max(0, Number(delay) || 0);
    timers.set(id, { id, runAt: now + ms, interval: null, fn });
    return id;
  }

  function setIntervalTimer(fn, delay = 0) {
    const id = nextId++;
    const ms = Math.max(1, Number(delay) || 1);
    timers.set(id, { id, runAt: now + ms, interval: ms, fn });
    return id;
  }

  function clearTimer(id) {
    timers.delete(id);
  }

  async function flushMicrotasks() {
    for (let i = 0; i < 24; i++) {
      await Promise.resolve();
    }
  }

  async function tick(ms) {
    await flushMicrotasks();
    const target = now + ms;
    while (true) {
      let earliest = null;
      for (const entry of timers.values()) {
        if (
          entry.runAt <= target &&
          (!earliest ||
            entry.runAt < earliest.runAt ||
            (entry.runAt === earliest.runAt && entry.id < earliest.id))
        ) {
          earliest = entry;
        }
      }
      if (!earliest) break;
      timers.delete(earliest.id);
      now = earliest.runAt;
      if (earliest.interval !== null) {
        timers.set(earliest.id, {
          id: earliest.id,
          runAt: now + earliest.interval,
          interval: earliest.interval,
          fn: earliest.fn,
        });
      }
      earliest.fn();
      await flushMicrotasks();
    }
    now = target;
    await flushMicrotasks();
  }

  function clearAll() {
    timers.clear();
  }

  return {
    setTimer,
    setIntervalTimer,
    clearTimer,
    tick,
    flushMicrotasks,
    clearAll,
    getNow: () => now,
    setNow: (v) => {
      now = v;
    },
  };
}

function createStorage(initial = {}) {
  const map = new Map(Object.entries(initial).map(([k, v]) => [k, String(v)]));
  return {
    getItem(key) {
      return map.has(String(key)) ? map.get(String(key)) : null;
    },
    setItem(key, value) {
      map.set(String(key), String(value));
    },
    removeItem(key) {
      map.delete(String(key));
    },
    clear() {
      map.clear();
    },
    _map: map,
  };
}

function createClassList(initial = []) {
  const set = new Set(initial);
  return {
    add(...names) {
      for (const n of names) set.add(n);
    },
    remove(...names) {
      for (const n of names) set.delete(n);
    },
    contains(name) {
      return set.has(name);
    },
    toggle(name, force) {
      if (force === undefined) {
        if (set.has(name)) {
          set.delete(name);
          return false;
        }
        set.add(name);
        return true;
      }
      if (force) {
        set.add(name);
        return true;
      }
      set.delete(name);
      return false;
    },
    _set: set,
  };
}

function splitSelectors(selector) {
  return selector
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

function matchesSimpleSelector(el, sel) {
  if (!el || !sel) return false;
  if (sel === '*') return true;

  let rem = sel;
  const tagMatch = rem.match(/^[a-zA-Z0-9_-]+/);
  if (tagMatch) {
    if ((el.tagName || '').toLowerCase() !== tagMatch[0].toLowerCase()) {
      return false;
    }
    rem = rem.slice(tagMatch[0].length);
  }

  while (rem.length > 0) {
    if (rem.startsWith('#')) {
      const m = rem.match(/^#([a-zA-Z0-9_-]+)/);
      if (!m || el.id !== m[1]) return false;
      rem = rem.slice(m[0].length);
    } else if (rem.startsWith('.')) {
      const m = rem.match(/^\.([a-zA-Z0-9_-]+)/);
      if (!m || !el.classList.contains(m[1])) return false;
      rem = rem.slice(m[0].length);
    } else if (rem.startsWith('[')) {
      const m = rem.match(/^\[([a-zA-Z0-9_-]+)(?:([*^$~|]?=)"?([^"\]]*)"?)?\]/);
      if (!m) return false;
      const attrName = m[1];
      const op = m[2];
      const expected = m[3];
      if (!el.hasAttribute(attrName)) return false;
      if (op) {
        const actual = String(el.getAttribute(attrName) || '');
        if (op === '=' && actual !== expected) return false;
        if (op === '^=' && !actual.startsWith(expected)) return false;
        if (op === '*=' && !actual.includes(expected)) return false;
        if (op === '$=' && !actual.endsWith(expected)) return false;
      }
      rem = rem.slice(m[0].length);
    } else if (rem.startsWith(':not(')) {
      const closeIdx = rem.indexOf(')');
      if (closeIdx === -1) return false;
      const inner = rem.slice(5, closeIdx);
      if (matchesSimpleSelector(el, inner)) return false;
      rem = rem.slice(closeIdx + 1);
    } else {
      return false;
    }
  }
  return true;
}

function createDomEnvironment({ buildHash = 'hash123', cookie = '', authenticated = true, readyState = 'loading' } = {}) {
  const docListeners = { capture: new Map(), bubble: new Map() };
  const body = createElement('body');
  const rootElements = [];

  function createElement(tagName, { id = '', classes = [], attrs = {}, text = '' } = {}) {
    const attributes = new Map();
    for (const [k, v] of Object.entries(attrs)) {
      attributes.set(k, String(v));
    }
    const dataset = new Proxy(
      {},
      {
        get(_t, prop) {
          const attrKey = 'data-' + String(prop).replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());
          return attributes.get(attrKey);
        },
        set(_t, prop, value) {
          const attrKey = 'data-' + String(prop).replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());
          attributes.set(attrKey, String(value));
          return true;
        },
        deleteProperty(_t, prop) {
          const attrKey = 'data-' + String(prop).replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());
          attributes.delete(attrKey);
          return true;
        },
      }
    );

    const listeners = { capture: new Map(), bubble: new Map() };
    const el = {
      tagName: tagName.toUpperCase(),
      id,
      classList: createClassList(classes),
      style: {},
      children: [],
      parentNode: null,
      parentElement: null,
      textContent: text,
      innerHTML: '',
      disabled: false,
      dataset,
      get href() {
        return attributes.get('href') || '';
      },
      set href(v) {
        attributes.set('href', String(v));
      },
      get type() {
        return attributes.get('type') || '';
      },
      set type(v) {
        attributes.set('type', String(v));
      },
      getAttribute(name) {
        if (name === 'id') return this.id || null;
        return attributes.has(name) ? attributes.get(name) : null;
      },
      setAttribute(name, value) {
        if (name === 'id') this.id = String(value);
        attributes.set(name, String(value));
      },
      hasAttribute(name) {
        if (name === 'id') return Boolean(this.id);
        return attributes.has(name);
      },
      removeAttribute(name) {
        attributes.delete(name);
      },
      appendChild(child) {
        child.parentNode = el;
        child.parentElement = el;
        el.children.push(child);
        return child;
      },
      matches(selector) {
        return splitSelectors(selector).some((s) => matchesSimpleSelector(el, s));
      },
      closest(selector) {
        let cur = el;
        while (cur) {
          if (typeof cur.matches === 'function' && cur.matches(selector)) {
            return cur;
          }
          cur = cur.parentElement;
        }
        return null;
      },
      querySelector(selector) {
        return el.querySelectorAll(selector)[0] || null;
      },
      querySelectorAll(selector) {
        const out = [];
        const visit = (node) => {
          for (const child of node.children || []) {
            if (child.matches(selector)) out.push(child);
            visit(child);
          }
        };
        visit(el);
        return out;
      },
      addEventListener(type, fn, opts) {
        const capture = opts === true || Boolean(opts && opts.capture);
        const map = capture ? listeners.capture : listeners.bubble;
        if (!map.has(type)) map.set(type, []);
        map.get(type).push(fn);
      },
      removeEventListener(type, fn, opts) {
        const capture = opts === true || Boolean(opts && opts.capture);
        const map = capture ? listeners.capture : listeners.bubble;
        const arr = map.get(type);
        if (!arr) return;
        const idx = arr.indexOf(fn);
        if (idx !== -1) arr.splice(idx, 1);
      },
      _captureListeners(type) {
        return [...(listeners.capture.get(type) || [])];
      },
      _bubbleListeners(type) {
        return [...(listeners.bubble.get(type) || [])];
      },
      dispatchEvent(event) {
        return dispatchDomEvent(el, event);
      },
    };
    return el;
  }

  function makeEvent(type, init = {}) {
    let defaultPrevented = false;
    let propagationStopped = false;
    let immediateStopped = false;
    const ev = {
      type,
      bubbles: init.bubbles !== undefined ? Boolean(init.bubbles) : true,
      cancelable: init.cancelable !== undefined ? Boolean(init.cancelable) : true,
      detail: init.detail,
      button: init.button !== undefined ? init.button : 0,
      ctrlKey: Boolean(init.ctrlKey),
      metaKey: Boolean(init.metaKey),
      shiftKey: Boolean(init.shiftKey),
      altKey: Boolean(init.altKey),
      target: null,
      currentTarget: null,
      get defaultPrevented() {
        return defaultPrevented;
      },
      preventDefault() {
        if (ev.cancelable) defaultPrevented = true;
      },
      stopPropagation() {
        propagationStopped = true;
      },
      stopImmediatePropagation() {
        propagationStopped = true;
        immediateStopped = true;
      },
      _isStopped() {
        return propagationStopped;
      },
      _isImmediateStopped() {
        return immediateStopped;
      },
    };
    return ev;
  }

  function getAncestorChain(target) {
    const chain = [];
    let cur = target;
    while (cur) {
      chain.unshift(cur);
      cur = cur.parentElement;
    }
    return chain;
  }

  function dispatchDomEvent(target, event) {
    event.target = target;
    const chain = getAncestorChain(target);

    // Capture phase: document -> ancestors (excluding target unless dispatched directly on document)
    if (target !== doc) {
      const docCaps = docListeners.capture.get(event.type) || [];
      event.currentTarget = doc;
      for (const fn of [...docCaps]) {
        if (event._isImmediateStopped()) break;
        fn.call(doc, event);
      }
      if (!event._isStopped()) {
        for (const node of chain.slice(0, -1)) {
          const caps = node._captureListeners ? node._captureListeners(event.type) : [];
          event.currentTarget = node;
          for (const fn of caps) {
            if (event._isImmediateStopped()) break;
            fn.call(node, event);
          }
          if (event._isStopped()) break;
        }
      }
    }

    // Target + Bubble phase
    if (!event._isStopped()) {
      const bubbleChain = target === doc ? [doc] : [...chain].reverse();
      for (let i = 0; i < bubbleChain.length; i++) {
        const node = bubbleChain[i];
        if (i > 0 && !event.bubbles) break;
        event.currentTarget = node;
        if (node === doc) {
          const list = i === 0
            ? [...(docListeners.capture.get(event.type) || []), ...(docListeners.bubble.get(event.type) || [])]
            : [...(docListeners.bubble.get(event.type) || [])];
          for (const fn of list) {
            if (event._isImmediateStopped()) break;
            fn.call(doc, event);
          }
        } else {
          const list = i === 0
            ? [...(node._captureListeners ? node._captureListeners(event.type) : []), ...(node._bubbleListeners ? node._bubbleListeners(event.type) : [])]
            : [...(node._bubbleListeners ? node._bubbleListeners(event.type) : [])];
          for (const fn of list) {
            if (event._isImmediateStopped()) break;
            fn.call(node, event);
          }
        }
        if (event._isStopped()) break;
      }
    }
    return !event.defaultPrevented;
  }

  // Build standard MonMon header/nav DOM elements
  const metaBuildHash = createElement('meta', { attrs: { name: 'monmon-build-hash', content: buildHash } });
  const updateBanner = createElement('div', { id: 'monmon-update-banner', classes: ['monmon-update-banner'] });
  updateBanner.style.display = 'none';
  const offlineBanner = createElement('div', { id: 'monmon-offline-banner', classes: ['monmon-offline-banner'] });
  offlineBanner.style.display = 'none';
  const offlineSubtext = createElement('span', { id: 'offline-banner-subtext', text: 'Read-Only Mode' });
  const offlineSnapshotAge = createElement('span', { id: 'offline-snapshot-age', text: '' });
  const offlineRetryFeedback = createElement('span', { id: 'offline-retry-feedback', text: '' });
  offlineRetryFeedback.style.display = 'none';
  const btnRetry = createElement('button', { id: 'btn-offline-retry', attrs: { type: 'button' }, text: 'Retry' });
  offlineBanner.appendChild(offlineSubtext);
  offlineBanner.appendChild(offlineSnapshotAge);
  offlineBanner.appendChild(offlineRetryFeedback);
  offlineBanner.appendChild(btnRetry);

  const timeTravelBanner = createElement('div', { id: 'monmon-time-travel-banner', classes: ['monmon-time-travel-banner'] });
  timeTravelBanner.style.display = cookie.includes('monmon_time_travel=') ? 'block' : 'none';

  const offlineToast = createElement('div', { id: 'monmon-offline-toast', classes: ['monmon-offline-toast'] });
  offlineToast.style.display = 'none';

  const navUserChip = createElement('div', { id: 'nav-user-chip', classes: ['user-chip', 'nav-user-chip'] });
  const warmIndicator = createElement('span', {
    id: 'offline-warm-indicator',
    classes: ['offline-warm-indicator'],
    attrs: { 'data-state': 'idle', 'aria-live': 'polite' },
  });
  warmIndicator.style.display = 'none';
  navUserChip.appendChild(warmIndicator);

  const mainNav = createElement('nav', { id: 'main-nav', classes: ['crumbs'] });
  mainNav.appendChild(navUserChip);

  const logoutAnchor = createElement('a', { attrs: { href: '/centralLogout' }, text: 'Log out' });

  rootElements.push(metaBuildHash);
  body.appendChild(updateBanner);
  body.appendChild(offlineBanner);
  body.appendChild(timeTravelBanner);
  body.appendChild(offlineToast);
  if (authenticated) {
    body.appendChild(mainNav);
  }
  body.appendChild(logoutAnchor);

  const doc = {
    readyState,
    cookie,
    body,
    documentElement: createElement('html'),
    getElementById(id) {
      const search = (node) => {
        if (node.id === id) return node;
        for (const c of node.children || []) {
          const hit = search(c);
          if (hit) return hit;
        }
        return null;
      };
      for (const r of rootElements) {
        const hit = search(r);
        if (hit) return hit;
      }
      return search(body);
    },
    querySelector(selector) {
      for (const r of rootElements) {
        if (r.matches(selector)) return r;
        const hit = r.querySelector(selector);
        if (hit) return hit;
      }
      return body.querySelector(selector);
    },
    querySelectorAll(selector) {
      const out = [];
      for (const r of rootElements) {
        if (r.matches(selector)) out.push(r);
        out.push(...r.querySelectorAll(selector));
      }
      out.push(...body.querySelectorAll(selector));
      return out;
    },
    addEventListener(type, fn, opts) {
      const capture = opts === true || Boolean(opts && opts.capture);
      const map = capture ? docListeners.capture : docListeners.bubble;
      if (!map.has(type)) map.set(type, []);
      map.get(type).push(fn);
    },
    removeEventListener(type, fn, opts) {
      const capture = opts === true || Boolean(opts && opts.capture);
      const map = capture ? docListeners.capture : docListeners.bubble;
      const arr = map.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx !== -1) arr.splice(idx, 1);
    },
    dispatchEvent(event) {
      return dispatchDomEvent(doc, event);
    },
  };

  return {
    document: doc,
    createElement,
    makeEvent,
    elements: {
      metaBuildHash,
      updateBanner,
      offlineBanner,
      offlineSubtext,
      offlineSnapshotAge,
      offlineRetryFeedback,
      btnRetry,
      timeTravelBanner,
      offlineToast,
      warmIndicator,
      mainNav,
      logoutAnchor,
    },
  };
}

function loadOfflineController({
  buildHash = 'hash123',
  cookie = '',
  authenticated = true,
  readyState = 'loading',
  onLine = true,
  localStorageInitial = {},
  sessionStorageInitial = {},
  cacheNames = ['monmon-static-hash123', 'monmon-pages-hash123'],
  fetchImpl = null,
  hasController = true,
  autoRespondSwClear = false,
  autoRespondSwPurge = false,
} = {}) {
  const timers = createFakeTimers();
  const dom = createDomEnvironment({ buildHash, cookie, authenticated, readyState });
  const localStorage = createStorage(localStorageInitial);
  const sessionStorage = createStorage(sessionStorageInitial);

  const windowListeners = new Map();
  const swListeners = new Map();
  const swMessagesSent = [];
  const swRegistrations = [];
  let swUpdateCalls = 0;
  let reloadCount = 0;
  const navigatedUrls = [];

  const deletedCaches = [];
  const cacheStore = new Set(cacheNames);
  const cachesMock = {
    async keys() {
      return Array.from(cacheStore);
    },
    async delete(name) {
      deletedCaches.push(name);
      return cacheStore.delete(name);
    },
  };

  const activeSw = {
    postMessage(msg) {
      swMessagesSent.push(structuredClone(msg));
      if (autoRespondSwPurge && msg && msg.type === 'MONMON_PURGE_CACHES') {
        Promise.resolve().then(() => dispatchSwMessage({ type: 'MONMON_CACHES_PURGED' }));
      }
      if (autoRespondSwClear && msg && msg.type === 'MONMON_CLEAR_USER_CACHES') {
        Promise.resolve().then(() => dispatchSwMessage({ type: 'MONMON_USER_CACHES_CLEARED' }));
      }
    },
  };

  const registrationObj = {
    active: activeSw,
    async update() {
      swUpdateCalls++;
    },
  };

  const navigatorMock = {
    onLine,
    serviceWorker: {
      controller: hasController ? activeSw : null,
      ready: Promise.resolve(registrationObj),
      async register(url, opts) {
        swRegistrations.push({ url, opts });
        return registrationObj;
      },
      async getRegistration() {
        return registrationObj;
      },
      addEventListener(type, fn) {
        if (!swListeners.has(type)) swListeners.set(type, []);
        swListeners.get(type).push(fn);
      },
      removeEventListener(type, fn) {
        const arr = swListeners.get(type);
        if (!arr) return;
        const idx = arr.indexOf(fn);
        if (idx !== -1) arr.splice(idx, 1);
      },
    },
  };

  const locationMock = {
    _href: `${ORIGIN}/spog`,
    origin: ORIGIN,
    pathname: '/spog',
    search: '',
    get href() {
      return this._href;
    },
    set href(val) {
      this._href = String(val);
      navigatedUrls.push(String(val));
    },
    assign(val) {
      this._href = String(val);
      navigatedUrls.push(String(val));
    },
    reload() {
      reloadCount++;
    },
  };

  const fetchCalls = [];
  let activeFetch =
    fetchImpl ||
    (async () =>
      new Response(JSON.stringify({ buildHash }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }));

  // Fake Date constructor that uses fake timers now()
  const RealDate = Date;
  class FakeDate extends RealDate {
    constructor(...args) {
      if (args.length === 0) {
        super(timers.getNow());
      } else {
        super(...args);
      }
    }
    static now() {
      return timers.getNow();
    }
    static parse(str) {
      return RealDate.parse(str);
    }
    static UTC(...args) {
      return RealDate.UTC(...args);
    }
  }

  function FakeAbortController() {
    let aborted = false;
    const abortListeners = [];
    return {
      signal: {
        get aborted() {
          return aborted;
        },
        addEventListener(type, fn) {
          if (type === 'abort') abortListeners.push(fn);
        },
        removeEventListener(type, fn) {
          const idx = abortListeners.indexOf(fn);
          if (idx !== -1) abortListeners.splice(idx, 1);
        },
      },
      abort() {
        if (aborted) return;
        aborted = true;
        for (const fn of [...abortListeners]) fn();
      },
    };
  }

  class FakeCustomEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.detail = init.detail;
    }
  }

  const windowEventsDispatched = [];
  const windowObj = {
    location: locationMock,
    navigator: navigatorMock,
    document: dom.document,
    localStorage,
    sessionStorage,
    caches: cachesMock,
    URL,
    CustomEvent: FakeCustomEvent,
    AbortController: FakeAbortController,
    addEventListener(type, fn) {
      if (!windowListeners.has(type)) windowListeners.set(type, []);
      windowListeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const arr = windowListeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx !== -1) arr.splice(idx, 1);
    },
    dispatchEvent(ev) {
      windowEventsDispatched.push(ev);
      const arr = windowListeners.get(ev.type) || [];
      for (const fn of [...arr]) fn(ev);
      return true;
    },
    setTimeout: timers.setTimer,
    clearTimeout: timers.clearTimer,
    setInterval: timers.setIntervalTimer,
    clearInterval: timers.clearTimer,
    requestIdleCallback(cb) {
      return timers.setTimer(() => cb({ didTimeout: false, timeRemaining: () => 50 }), 0);
    },
    fetch(input, init) {
      fetchCalls.push({ input, init });
      return activeFetch(input, init);
    },
  };

  const context = {
    window: windowObj,
    self: windowObj,
    document: dom.document,
    navigator: navigatorMock,
    localStorage,
    sessionStorage,
    caches: cachesMock,
    location: locationMock,
    URL,
    Date: FakeDate,
    CustomEvent: FakeCustomEvent,
    AbortController: FakeAbortController,
    Promise,
    Set,
    Map,
    Array,
    Object,
    JSON,
    Math,
    Number,
    String,
    Boolean,
    RegExp,
    Error,
    console: {
      log() {},
      warn() {},
      error() {},
    },
    setTimeout: timers.setTimer,
    clearTimeout: timers.clearTimer,
    setInterval: timers.setIntervalTimer,
    clearInterval: timers.clearTimer,
    requestIdleCallback: windowObj.requestIdleCallback,
    fetch: windowObj.fetch,
  };

  const source = fs.readFileSync(OFFLINE_MODE_PATH, 'utf8');
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'offline-mode.js' });

  function dispatchSwMessage(data) {
    const arr = swListeners.get('message') || [];
    for (const fn of [...arr]) {
      fn({ data });
    }
  }

  function dispatchWindowEvent(type, extra = {}) {
    windowObj.dispatchEvent({ type, ...extra });
  }

  function setOnline(val) {
    navigatorMock.onLine = val;
    dispatchWindowEvent(val ? 'online' : 'offline');
  }

  function setFetchImpl(fn) {
    activeFetch = fn;
  }

  return {
    window: windowObj,
    document: dom.document,
    elements: dom.elements,
    createElement: dom.createElement,
    makeEvent: dom.makeEvent,
    timers,
    localStorage,
    sessionStorage,
    caches: cachesMock,
    cacheStore,
    deletedCaches,
    swMessagesSent,
    swRegistrations,
    fetchCalls,
    windowEventsDispatched,
    navigatedUrls,
    get swUpdateCalls() {
      return swUpdateCalls;
    },
    get reloadCount() {
      return reloadCount;
    },
    dispatchSwMessage,
    dispatchWindowEvent,
    setOnline,
    setFetchImpl,
    cleanup() {
      timers.clearAll();
    },
  };
}

let activeEnvs = [];
afterEach(() => {
  for (const env of activeEnvs) env.cleanup();
  activeEnvs = [];
});

function createEnv(opts) {
  const env = loadOfflineController(opts);
  activeEnvs.push(env);
  return env;
}

describe('MonMon Offline Mode Client Controller (offline-mode.js)', () => {
  it('1. checkVersionAndSync matching buildHash stays online, clears monmon_updating_to_hash, and schedules pre-warm', async () => {
    const env = createEnv({
      buildHash: 'v42',
      readyState: 'complete',
      sessionStorageInitial: { monmon_updating_to_hash: 'v42' },
    });

    await env.window.monmonOfflineMode.checkVersionAndSync();
    await env.timers.flushMicrotasks();

    assert.equal(env.window.monmonIsOffline(), false);
    assert.equal(env.document.body.classList.contains('is-offline'), false);
    assert.equal(env.sessionStorage.getItem('monmon_updating_to_hash'), null);
    assert.equal(env.reloadCount, 0);

    // Advance timers/idle callback to fire scheduled pre-warm
    await env.timers.tick(2500);
    const prewarmMsg = env.swMessagesSent.find((m) => m.type === 'MONMON_PREWARM');
    assert.ok(prewarmMsg, 'Expected MONMON_PREWARM message to be scheduled when hashes match');
    assert.equal(prewarmMsg.urls[0], '/spog');
    assert.equal(prewarmMsg.urls[1], '/nommon');
    assert.equal(prewarmMsg.urls[2], '/api/nommon/plan?lockedOnly=true');
  });

  it('2. [Review Focus #1] checkVersionAndSync on mismatch purges caches, sets sessionStorage guard, reloads ONCE, and avoids reload loop', async () => {
    const env = createEnv({
      buildHash: 'oldHash',
      readyState: 'loading',
      cacheNames: ['monmon-static-oldHash', 'monmon-pages-oldHash', 'other-cache'],
      autoRespondSwPurge: true,
      fetchImpl: async () =>
        new Response(JSON.stringify({ buildHash: 'newHash' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    });

    // First call: mismatch -> purges caches, records guard, calls reload() once
    await env.window.monmonOfflineMode.checkVersionAndSync();
    await env.timers.flushMicrotasks();

    assert.equal(env.sessionStorage.getItem('monmon_updating_to_hash'), 'newHash');
    assert.equal(env.reloadCount, 1);
    assert.ok(
      env.swMessagesSent.some((m) => m.type === 'MONMON_PURGE_CACHES'),
      'Expected MONMON_PURGE_CACHES sent to SW'
    );
    assert.equal(env.cacheStore.has('monmon-static-oldHash'), false);
    assert.equal(env.cacheStore.has('monmon-pages-oldHash'), false);
    assert.equal(env.cacheStore.has('other-cache'), true);

    // Second call with same serverHash already in sessionStorage: must NOT reload again
    await env.window.monmonOfflineMode.checkVersionAndSync();
    await env.timers.flushMicrotasks();

    assert.equal(env.reloadCount, 1, 'Reload must not be called a second time for the same serverHash');
  });

  it('3. checkVersionAndSync when /api/version rejects or times out enters body.is-offline, shows banner, and displays relative snapshot age', async () => {
    // 3a. Network rejection with known monmon_last_warm_at (5 minutes ago)
    const envReject = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      localStorageInitial: {
        monmon_last_warm_at: new Date(1_760_000_000_000 - 5 * 60 * 1000).toISOString(),
      },
      fetchImpl: async () => {
        throw new Error('Network unreachable');
      },
    });

    envReject.document.dispatchEvent(envReject.makeEvent('DOMContentLoaded'));
    await envReject.timers.flushMicrotasks();
    assert.equal(envReject.document.body.classList.contains('is-offline'), true);
    assert.equal(envReject.window.monmonIsOffline(), true);
    assert.notEqual(envReject.elements.offlineBanner.style.display, 'none');
    assert.match(envReject.elements.offlineSnapshotAge.textContent, /5m ago/i);
    assert.ok(
      envReject.windowEventsDispatched.some((e) => e.type === 'monmon:offline-change' && e.detail && e.detail.offline === true),
      'Expected monmon:offline-change event with offline: true'
    );

    // Retry while still offline flashes "Still offline" feedback; Retry after recovery reloads
    envReject.elements.btnRetry.dispatchEvent(envReject.makeEvent('click', { bubbles: true, cancelable: true }));
    await envReject.timers.flushMicrotasks();
    assert.equal(envReject.elements.offlineRetryFeedback.textContent, 'Still offline');
    assert.equal(envReject.reloadCount, 0);

    envReject.setFetchImpl(async () =>
      new Response(JSON.stringify({ buildHash: 'v1' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    envReject.elements.btnRetry.dispatchEvent(envReject.makeEvent('click', { bubbles: true, cancelable: true }));
    await envReject.timers.flushMicrotasks();
    assert.equal(envReject.reloadCount, 1);

    // 3b. Hanging Lie-Fi timeout (>2500ms)
    const envTimeout = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      fetchImpl: (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal.addEventListener('abort', () => {
            reject(new Error('Aborted due to timeout'));
          });
        }),
    });

    const checkPromise = envTimeout.window.monmonOfflineMode.checkVersionAndSync();
    await envTimeout.timers.tick(2500);
    await checkPromise;

    assert.equal(envTimeout.document.body.classList.contains('is-offline'), true);
    assert.notEqual(envTimeout.elements.offlineBanner.style.display, 'none');
    assert.ok(envTimeout.elements.offlineSnapshotAge.textContent.length > 0);
  });

  it('4. Warming indicator lifecycle: 🔥 during pre-warm, ✓ on MONMON_PREWARM_DONE + localStorage update + hide after fade, immediate hide on MONMON_PREWARM_ABORTED', async () => {
    const env = createEnv({ buildHash: 'v1', readyState: 'loading' });
    const ind = env.elements.warmIndicator;

    // Start pre-warm -> visible with 🔥 and data-state="warming"
    env.window.monmonOfflineMode.triggerPrewarm();
    assert.notEqual(ind.style.display, 'none');
    assert.equal(ind.getAttribute('data-state'), 'warming');
    assert.equal(ind.textContent, '🔥');

    // Receive MONMON_PREWARM_DONE -> switches to ✓ (data-state="done") & sets monmon_last_warm_at
    const warmedIso = new Date(env.timers.getNow()).toISOString();
    env.dispatchSwMessage({ type: 'MONMON_PREWARM_DONE', total: 17, warmedAt: warmedIso });
    assert.equal(ind.getAttribute('data-state'), 'done');
    assert.equal(ind.textContent, '✓');
    assert.notEqual(ind.style.display, 'none');
    assert.equal(env.localStorage.getItem('monmon_last_warm_at'), warmedIso);

    // After fade delay (~2.5s), indicator hides
    await env.timers.tick(2600);
    assert.equal(ind.style.display, 'none');

    // Start forced pre-warm again, then receive MONMON_PREWARM_ABORTED -> hides immediately without showing ✓
    env.window.monmonOfflineMode.triggerPrewarm(true);
    assert.equal(ind.getAttribute('data-state'), 'warming');
    assert.equal(ind.textContent, '🔥');

    env.dispatchSwMessage({ type: 'MONMON_PREWARM_ABORTED', reason: 'network_error' });
    assert.equal(ind.style.display, 'none');
    assert.notEqual(ind.textContent, '✓');
    assert.equal(ind.getAttribute('data-state'), 'idle');
  });

  it('5. Pre-warm throttling: skips if monmon_last_warm_at < 15m ago (unless force=true or version changed), and skips completely during Time Travel', async () => {
    // 5a. Throttled within 15 minutes
    const fiveMinAgo = new Date(1_760_000_000_000 - 5 * 60 * 1000).toISOString();
    const envThrottled = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      localStorageInitial: { monmon_last_warm_at: fiveMinAgo },
    });

    envThrottled.window.monmonOfflineMode.triggerPrewarm(false);
    assert.equal(
      envThrottled.swMessagesSent.filter((m) => m.type === 'MONMON_PREWARM').length,
      0,
      'Should skip pre-warming when warmed < 15 minutes ago'
    );

    // force=true bypasses throttle
    envThrottled.window.monmonOfflineMode.triggerPrewarm(true);
    assert.equal(
      envThrottled.swMessagesSent.filter((m) => m.type === 'MONMON_PREWARM').length,
      1,
      'force=true should bypass 15-minute throttle'
    );

    // Version changed bypasses throttle
    const envVersionChanged = createEnv({
      buildHash: 'v2',
      readyState: 'complete',
      localStorageInitial: {
        monmon_last_warm_at: fiveMinAgo,
        monmon_last_build_hash: 'v1',
      },
      fetchImpl: async () =>
        new Response(JSON.stringify({ buildHash: 'v2' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    });
    await envVersionChanged.window.monmonOfflineMode.checkVersionAndSync();
    await envVersionChanged.timers.tick(2500);
    assert.equal(
      envVersionChanged.swMessagesSent.filter((m) => m.type === 'MONMON_PREWARM').length,
      1,
      'Version change should bypass 15-minute throttle'
    );

    // 5b. Skips completely when monmon_time_travel cookie is present (even with force=true)
    const envTimeTravel = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      cookie: 'monmon_time_travel=2024-03-15',
    });
    envTimeTravel.window.monmonOfflineMode.triggerPrewarm(true);
    assert.equal(
      envTimeTravel.swMessagesSent.filter((m) => m.type === 'MONMON_PREWARM').length,
      0,
      'Must skip pre-warming completely during Time Travel'
    );
  });

  it('6. [Review Focus #5] Time Travel offline suppression: shows paused note in offline banner and hides #monmon-time-travel-banner', () => {
    const env = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      cookie: 'monmon_time_travel=2024-03-15',
    });

    assert.equal(env.elements.timeTravelBanner.style.display, 'block');

    env.window.monmonOfflineMode.enterOffline('2026-11-19T10:00:00Z');

    assert.equal(env.document.body.classList.contains('is-offline'), true);
    assert.equal(
      env.elements.offlineSubtext.textContent,
      'Read-Only Mode (Live snapshot — Time Travel paused offline)'
    );
    assert.equal(env.elements.timeTravelBanner.style.display, 'none');
  });

  it('7. Read-only form/action blocking: prevents normal <form> submit and shows toast when body.is-offline; allows <form data-offline-allowed="true">', () => {
    const env = createEnv({ buildHash: 'v1', readyState: 'loading' });
    env.document.body.classList.add('is-offline');

    const normalForm = env.createElement('form', { attrs: { method: 'POST', action: '/outgoings/save' } });
    const allowedForm = env.createElement('form', {
      attrs: { method: 'POST', action: '/local', 'data-offline-allowed': 'true' },
    });
    env.document.body.appendChild(normalForm);
    env.document.body.appendChild(allowedForm);

    const blockedEv = env.makeEvent('submit', { bubbles: true, cancelable: true });
    normalForm.dispatchEvent(blockedEv);
    assert.equal(blockedEv.defaultPrevented, true);
    assert.notEqual(env.elements.offlineToast.style.display, 'none');
    assert.match(env.elements.offlineToast.textContent, /offline/i);

    const allowedEv = env.makeEvent('submit', { bubbles: true, cancelable: true });
    allowedForm.dispatchEvent(allowedEv);
    assert.equal(allowedEv.defaultPrevented, false);

    // Also verify NomMon / Transfer local controls are NOT blocked while edit links and DELETE buttons ARE blocked
    const nommonCheck = env.createElement('input', { classes: ['nommon-item-check'], attrs: { type: 'checkbox' } });
    const transferCheck = env.createElement('input', { id: 'chk-next-1', attrs: { type: 'checkbox' } });
    const editLink = env.createElement('a', { attrs: { href: '/editOutgoing/42' } });
    const deleteBtn = env.createElement('button', { attrs: { type: 'button', 'data-method': 'DELETE', 'data-action': '/binOutgoing/42' } });
    env.document.body.appendChild(nommonCheck);
    env.document.body.appendChild(transferCheck);
    env.document.body.appendChild(editLink);
    env.document.body.appendChild(deleteBtn);

    const nommonClick = env.makeEvent('click', { bubbles: true, cancelable: true });
    nommonCheck.dispatchEvent(nommonClick);
    assert.equal(nommonClick.defaultPrevented, false);

    const transferClick = env.makeEvent('click', { bubbles: true, cancelable: true });
    transferCheck.dispatchEvent(transferClick);
    assert.equal(transferClick.defaultPrevented, false);

    const editClick = env.makeEvent('click', { bubbles: true, cancelable: true });
    editLink.dispatchEvent(editClick);
    assert.equal(editClick.defaultPrevented, true);

    const deleteClick = env.makeEvent('click', { bubbles: true, cancelable: true });
    deleteBtn.dispatchEvent(deleteClick);
    assert.equal(deleteClick.defaultPrevented, true);
  });

  it('8. Logout cache clearing: clicking a[href="/centralLogout"] online posts MONMON_CLEAR_USER_CACHES and removes monmon_last_warm_at before navigating; offline is blocked', async () => {
    // 8a. Online logout click
    const envOnline = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      localStorageInitial: { monmon_last_warm_at: new Date(1_760_000_000_000).toISOString() },
      autoRespondSwClear: true,
    });

    const onlineLogoutEv = envOnline.makeEvent('click', { bubbles: true, cancelable: true });
    envOnline.elements.logoutAnchor.dispatchEvent(onlineLogoutEv);
    assert.equal(onlineLogoutEv.defaultPrevented, true, 'Online logout should pause navigation briefly to clear caches');
    await envOnline.timers.flushMicrotasks();

    assert.ok(
      envOnline.swMessagesSent.some((m) => m.type === 'MONMON_CLEAR_USER_CACHES'),
      'Expected MONMON_CLEAR_USER_CACHES posted to SW'
    );
    assert.equal(envOnline.localStorage.getItem('monmon_last_warm_at'), null);
    assert.ok(
      envOnline.navigatedUrls.some((u) => u.endsWith('/centralLogout')),
      'Expected navigation to /centralLogout after clearing caches'
    );

    // 8b. Offline logout click is blocked
    const envOffline = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      localStorageInitial: { monmon_last_warm_at: new Date(1_760_000_000_000).toISOString() },
    });
    envOffline.window.monmonOfflineMode.enterOffline();

    const offlineLogoutEv = envOffline.makeEvent('click', { bubbles: true, cancelable: true });
    envOffline.elements.logoutAnchor.dispatchEvent(offlineLogoutEv);
    await envOffline.timers.flushMicrotasks();

    assert.equal(offlineLogoutEv.defaultPrevented, true);
    assert.equal(envOffline.navigatedUrls.length, 0, 'Must not navigate to /centralLogout while offline');
    assert.notEqual(envOffline.elements.offlineToast.style.display, 'none');
  });

  it('9. [Fix #1] Online recovery reloads when initial load was offline/Lie-Fi cached HTML, and exits offline cleanly without reload when initial load was live', async () => {
    // 9a. Initial load fails /api/version under Lie-Fi (MONMON_OFFLINE_SERVED fired before script init)
    const envLieFi = createEnv({
      buildHash: 'v1',
      readyState: 'complete',
      onLine: true,
      fetchImpl: async () => {
        throw new Error('Lie-Fi timeout/failure');
      },
    });
    await envLieFi.timers.flushMicrotasks();

    assert.equal(envLieFi.document.body.classList.contains('is-offline'), true);
    assert.equal(envLieFi.reloadCount, 0);

    // Later connectivity returns via window 'online' event -> must reload to fetch live HTML
    envLieFi.setFetchImpl(async () =>
      new Response(JSON.stringify({ buildHash: 'v1' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    envLieFi.setOnline(true);
    await envLieFi.timers.flushMicrotasks();

    assert.equal(envLieFi.reloadCount, 1, 'Expected reload when recovering from cached offline/Lie-Fi initial load');

    // 9a-ii. Initial load starts with navigator.onLine === false -> comes online -> reloads
    const envBootOffline = createEnv({
      buildHash: 'v1',
      readyState: 'complete',
      onLine: false,
      fetchImpl: async () =>
        new Response(JSON.stringify({ buildHash: 'v1' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    });
    assert.equal(envBootOffline.document.body.classList.contains('is-offline'), true);
    envBootOffline.setOnline(true);
    await envBootOffline.timers.flushMicrotasks();
    assert.equal(envBootOffline.reloadCount, 1, 'Expected reload when recovering from navigator.onLine === false boot');

    // 9b. Initial load succeeds online -> goes offline briefly -> comes back online -> exits offline WITHOUT reload
    const envLiveFirst = createEnv({
      buildHash: 'v1',
      readyState: 'complete',
      onLine: true,
      fetchImpl: async () =>
        new Response(JSON.stringify({ buildHash: 'v1' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    });
    await envLiveFirst.timers.flushMicrotasks();
    assert.equal(envLiveFirst.document.body.classList.contains('is-offline'), false);

    // Drop offline temporarily without navigating
    envLiveFirst.setOnline(false);
    assert.equal(envLiveFirst.document.body.classList.contains('is-offline'), true);

    // Come back online -> calls exitOffline() without unnecessary reload
    envLiveFirst.setOnline(true);
    await envLiveFirst.timers.flushMicrotasks();

    assert.equal(envLiveFirst.reloadCount, 0, 'Must NOT reload when initial load succeeded live online');
    assert.equal(envLiveFirst.document.body.classList.contains('is-offline'), false);
    assert.equal(envLiveFirst.elements.offlineBanner.style.display, 'none');
    assert.ok(
      envLiveFirst.windowEventsDispatched.some(
        (e) => e.type === 'monmon:offline-change' && e.detail && e.detail.offline === false
      ),
      'Expected monmon:offline-change event with offline: false on recovery'
    );
  });

  it('10. [Task 6] offline-mode.js dispatches monmon:offline-change with { offline: true/false } only on real transitions', async () => {
    const env = createEnv({
      buildHash: 'v1',
      readyState: 'complete',
      onLine: true,
    });
    await env.timers.flushMicrotasks();

    const initialEvents = env.windowEventsDispatched.filter((e) => e.type === 'monmon:offline-change');
    assert.equal(initialEvents.length, 0, 'Online boot should not emit offline-change');

    // Transition to offline
    env.window.monmonOfflineMode.enterOffline();
    // Duplicate enterOffline should NOT emit a second event
    env.window.monmonOfflineMode.enterOffline();
    // Transition back to online
    env.window.monmonOfflineMode.exitOffline();
    // Duplicate exitOffline should NOT emit a second event
    env.window.monmonOfflineMode.exitOffline();

    const changeEvents = env.windowEventsDispatched.filter((e) => e.type === 'monmon:offline-change');
    assert.deepEqual(
      changeEvents.map((e) => ({ offline: Boolean(e.detail && e.detail.offline) })),
      [{ offline: true }, { offline: false }]
    );
  });

  it('11. [Task 6] waitingListModal.js skips fetch(/api/user/accessStatus) when navigator.onLine === false or body.is-offline', async () => {
    const waitingListPath = path.join(ROOT_DIR, 'public/javascripts/waitingListModal.js');
    const waitingListSrc = fs.readFileSync(waitingListPath, 'utf8');

    // 11a. navigator.onLine === false at load
    const envNavOffline = createEnv({
      buildHash: 'v1',
      readyState: 'complete',
      onLine: false,
      fetchImpl: async () => new Response(JSON.stringify({ authenticated: true, approved: true }), { status: 200 }),
    });
    const fetchBeforeA = envNavOffline.fetchCalls.length;
    vm.runInNewContext(waitingListSrc, {
      window: envNavOffline.window,
      document: envNavOffline.document,
      navigator: envNavOffline.window.navigator,
      fetch: envNavOffline.window.fetch,
      setTimeout: envNavOffline.window.setTimeout,
      clearTimeout: envNavOffline.window.clearTimeout,
      setInterval: envNavOffline.window.setInterval,
      clearInterval: envNavOffline.window.clearInterval,
      alert() {},
    });
    await envNavOffline.timers.flushMicrotasks();
    assert.equal(
      envNavOffline.fetchCalls.slice(fetchBeforeA).filter((c) => String(c.input).includes('/api/user/accessStatus')).length,
      0,
      'Must skip /api/user/accessStatus when navigator.onLine === false'
    );

    // 11b. body.is-offline when navigator.onLine === true (Lie-Fi)
    const envBodyOffline = createEnv({
      buildHash: 'v1',
      readyState: 'complete',
      onLine: true,
      fetchImpl: async () => new Response(JSON.stringify({ authenticated: true, approved: true }), { status: 200 }),
    });
    envBodyOffline.window.monmonOfflineMode.enterOffline();
    const fetchBeforeB = envBodyOffline.fetchCalls.length;
    vm.runInNewContext(waitingListSrc, {
      window: envBodyOffline.window,
      document: envBodyOffline.document,
      navigator: envBodyOffline.window.navigator,
      fetch: envBodyOffline.window.fetch,
      setTimeout: envBodyOffline.window.setTimeout,
      clearTimeout: envBodyOffline.window.clearTimeout,
      setInterval: envBodyOffline.window.setInterval,
      clearInterval: envBodyOffline.window.clearInterval,
      alert() {},
    });
    envBodyOffline.window.monmonCheckWaitingList(true);
    await envBodyOffline.timers.flushMicrotasks();
    assert.equal(
      envBodyOffline.fetchCalls.slice(fetchBeforeB).filter((c) => String(c.input).includes('/api/user/accessStatus')).length,
      0,
      'Must skip /api/user/accessStatus when body.is-offline is active'
    );
  });

  it('12. [Task 6] NomMon & Transfer Window local interactive controls and [data-offline-allowed] are NOT blocked while body.is-offline', () => {
    const env = createEnv({ buildHash: 'v1', readyState: 'loading' });
    env.document.body.classList.add('is-offline');

    const localControls = [
      env.createElement('button', { id: 'btn-uncheck-all', attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { classes: ['nommon-tab-btn'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('input', { classes: ['nommon-item-check'], attrs: { type: 'checkbox' } }),
      env.createElement('input', { id: 'chk-next-42', attrs: { type: 'checkbox' } }),
      env.createElement('button', { id: 'next-clear-btn', classes: ['btn-clear-checks'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { id: 'last-clear-btn', classes: ['btn-clear-checks'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { classes: ['btn-copy-amt'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { classes: ['breakdown-toggle-btn'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { id: 'tab-btn-next', classes: ['tab-btn'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { id: 'tab-btn-last', classes: ['tab-btn'], attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
      env.createElement('button', { attrs: { type: 'button', 'data-offline-allowed': 'true' } }),
    ];

    for (const el of localControls) {
      env.document.body.appendChild(el);
      const clickEv = env.makeEvent('click', { bubbles: true, cancelable: true });
      el.dispatchEvent(clickEv);
      assert.equal(clickEv.defaultPrevented, false, `Expected ${el.id || el.tagName} click not to be prevented offline`);
    }

    // Verify templates explicitly mark local-only buttons with data-offline-allowed="true"
    const nommonHtml = fs.readFileSync(path.join(ROOT_DIR, 'modules/monmon-rs/templates/nommon.html'), 'utf8');
    assert.match(nommonHtml, /id="btn-uncheck-all"[^>]*data-offline-allowed="true"/);
    const nommonTabMatches = nommonHtml.match(/class="nommon-tab-btn[^"]*"[^>]*>/g) || [];
    assert.equal(nommonTabMatches.length, 3);
    for (const tag of nommonTabMatches) {
      assert.match(tag, /data-offline-allowed="true"/);
    }

    const transfersHtml = fs.readFileSync(path.join(ROOT_DIR, 'modules/monmon-rs/templates/transfers.html'), 'utf8');
    assert.match(transfersHtml, /id="tab-btn-next"[^>]*data-offline-allowed="true"|class="tab-btn[^"]*"[^>]*data-offline-allowed="true"[^>]*id="tab-btn-next"/);
    assert.match(transfersHtml, /id="tab-btn-last"[^>]*data-offline-allowed="true"|class="tab-btn[^"]*"[^>]*data-offline-allowed="true"[^>]*id="tab-btn-last"/);
    assert.match(transfersHtml, /id="next-clear-btn"[^>]*data-offline-allowed="true"/);
    assert.match(transfersHtml, /id="last-clear-btn"[^>]*data-offline-allowed="true"/);
    const copyBtns = transfersHtml.match(/<button[^>]*class="btn-copy-amt"[^>]*>/g) || [];
    assert.ok(copyBtns.length > 0);
    for (const tag of copyBtns) {
      assert.match(tag, /data-offline-allowed="true"/);
    }
    const breakdownBtns = transfersHtml.match(/<button[^>]*class="breakdown-toggle-btn"[^>]*>/g) || [];
    assert.ok(breakdownBtns.length > 0);
    for (const tag of breakdownBtns) {
      assert.match(tag, /data-offline-allowed="true"/);
    }
  });

  it('13. [Task 6] global-search.js disables #global-search-input with "Search unavailable offline" placeholder when offline and restores on online', async () => {
    const env = createEnv({ buildHash: 'v1', readyState: 'complete', onLine: true });
    await env.timers.flushMicrotasks();

    const container = env.createElement('div', { id: 'header-search-container' });
    const input = env.createElement('input', {
      id: 'global-search-input',
      attrs: { type: 'text', placeholder: 'Search... (/)' },
    });
    input.placeholder = 'Search... (/)';
    input.value = '';
    input.focus = () => {};
    input.blur = () => {};
    input.select = () => {};
    const dropdown = env.createElement('div', { id: 'header-search-dropdown' });
    dropdown.style.display = 'none';
    const resultsList = env.createElement('div', { id: 'header-search-results-list' });
    const spinner = env.createElement('span', { id: 'header-search-spinner' });
    const clearBtn = env.createElement('button', { id: 'header-search-clear' });
    const kbd = env.createElement('kbd', { id: 'header-search-kbd' });
    container.contains = (node) => node === container || node === input;
    container.appendChild(input);
    container.appendChild(dropdown);
    container.appendChild(resultsList);
    container.appendChild(spinner);
    container.appendChild(clearBtn);
    container.appendChild(kbd);
    env.document.body.appendChild(container);

    const searchSrc = fs.readFileSync(path.join(ROOT_DIR, 'public/javascripts/global-search.js'), 'utf8');
    vm.runInNewContext(searchSrc, {
      window: env.window,
      document: env.document,
      URLSearchParams,
      fetch: env.window.fetch,
      setTimeout: env.window.setTimeout,
      clearTimeout: env.window.clearTimeout,
    });

    assert.equal(input.disabled, false);
    assert.equal(input.placeholder, 'Search... (/)');

    env.window.monmonOfflineMode.enterOffline();
    assert.equal(input.disabled, true);
    assert.equal(input.placeholder, 'Search unavailable offline');

    env.window.monmonOfflineMode.exitOffline();
    assert.equal(input.disabled, false);
    assert.equal(input.placeholder, 'Search... (/)');
  });

  it('14. [Task 6] nommon.js renders cached plan badge, skips postcodes.io offline, handles offline cache-miss & force-regenerate, and locks mutating controls while keeping checklists/tabs enabled', async () => {
    const env = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      onLine: true,
      fetchImpl: async (url) => {
        if (String(url).startsWith('/api/nommon/plan')) {
          return new Response(
            JSON.stringify({
              locked: true,
              plan: {
                planTitle: 'Cached Sainsbury Plan',
                weeklyBudget: 50,
                shoppingList: [{ name: 'Oats', category: 'Bakery', quantity: 1, estimatedPrice: 1.5 }],
                days: [{ dayName: 'Monday', breakfast: 'Oats', lunch: 'Soup', dinner: 'Pasta' }],
                recipes: [{ title: 'Oats', ingredientsUsed: ['Oats'], instructions: ['Cook'] }],
              },
            }),
            {
              status: 200,
              headers: {
                'Content-Type': 'application/json',
                'X-MonMon-Offline': '1',
              },
            }
          );
        }
        throw new Error('Offline');
      },
    });

    function addInnerHtmlTracking(el) {
      let raw = '';
      Object.defineProperty(el, 'innerHTML', {
        get() { return raw; },
        set(val) {
          raw = String(val);
          el.children = [];
          if (raw.includes('class="nommon-item-check"')) {
            const cb = env.createElement('input', { classes: ['nommon-item-check'], attrs: { type: 'checkbox', 'data-price': '1.5', 'data-qty': '1' } });
            cb.checked = false;
            addInnerHtmlTracking(cb);
            el.appendChild(cb);
          }
          if (raw.includes('id="btn-lock-nommon"')) {
            const b = env.createElement('button', { id: 'btn-lock-nommon', attrs: { type: 'button' } });
            el.appendChild(b);
          }
          if (raw.includes('id="btn-unlock-nommon"')) {
            const b = env.createElement('button', { id: 'btn-unlock-nommon', attrs: { type: 'button' } });
            el.appendChild(b);
          }
        },
        configurable: true,
      });
      return el;
    }

    const container = env.createElement('div', { id: 'nommon-container', attrs: { 'data-budget': '50', 'data-configured': 'true' } });
    const btnRegen = env.createElement('button', { id: 'btn-regenerate-nommon', attrs: { type: 'button' } });
    const refreshIcon = env.createElement('i', { id: 'nommon-refresh-icon' });
    const lockWrapper = addInnerHtmlTracking(env.createElement('div', { id: 'nommon-lock-wrapper' }));
    const budgetTierBadge = env.createElement('span', { id: 'nommon-budget-tier-badge' });
    const budgetInput = env.createElement('input', { id: 'nommon-budget-input', attrs: { type: 'number' } });
    budgetInput.value = '50';
    const peopleSelect = env.createElement('select', { id: 'nommon-people-select' });
    peopleSelect.value = '1';
    const budgetSubtext = env.createElement('div', { id: 'nommon-budget-subtext' });
    const basketVal = env.createElement('div', { id: 'nommon-basket-val' });
    const basketSubtitle = addInnerHtmlTracking(env.createElement('div', { id: 'nommon-basket-subtitle' }));
    const bufferVal = env.createElement('div', { id: 'nommon-buffer-val' });
    const checkedCounter = env.createElement('div', { id: 'nommon-checked-counter' });
    const checkedSpend = env.createElement('div', { id: 'nommon-checked-spend' });
    const postcodeInput = env.createElement('input', { id: 'nommon-postcode-input', attrs: { type: 'text' } });
    postcodeInput.value = 'EC1A 1BB';
    const btnSavePostcode = env.createElement('button', { id: 'btn-save-postcode', attrs: { type: 'button' } });
    const finderLink = env.createElement('a', { id: 'link-sainsburys-finder', attrs: { href: 'https://stores.sainsburys.co.uk/' } });
    const mapsLink = env.createElement('a', { id: 'link-google-maps', attrs: { href: 'https://www.google.com/maps' } });
    const loadingEl = addInnerHtmlTracking(env.createElement('div', { id: 'nommon-loading' }));
    const loadingText = env.createElement('p', { id: 'nommon-loading-text' });
    loadingEl.appendChild(loadingText);
    const contentEl = env.createElement('div', { id: 'nommon-content' });
    const tabShopping = env.createElement('button', { classes: ['nommon-tab-btn', 'active'], attrs: { type: 'button', 'data-tab': 'tab-shopping', 'data-offline-allowed': 'true' } });
    const panelShopping = env.createElement('div', { id: 'tab-shopping', classes: ['nommon-tab-panel'] });
    const btnUncheck = env.createElement('button', { id: 'btn-uncheck-all', attrs: { type: 'button', 'data-offline-allowed': 'true' } });
    const shoppingCategories = env.createElement('div', { id: 'shopping-list-categories' });
    const origAppend = shoppingCategories.appendChild.bind(shoppingCategories);
    shoppingCategories.appendChild = (child) => {
      if (child && !Object.getOwnPropertyDescriptor(child, 'innerHTML')?.set) {
        // wrap appended catBox children too
      }
      return origAppend(child);
    };
    const daysGrid = env.createElement('div', { id: 'days-grid' });
    const recipesContainer = env.createElement('div', { id: 'recipes-container' });
    const tabItemCount = env.createElement('span', { id: 'tab-item-count' });
    const tabRecipeCount = env.createElement('span', { id: 'tab-recipe-count' });
    const planTitle = env.createElement('h3', { id: 'plan-title-display' });
    const nutritionHighlight = env.createElement('div', { id: 'nutrition-highlight-display' });

    const origCreate = env.document.createElement;
    const patchedDocument = {
      ...env.document,
      createElement(tag) {
        return addInnerHtmlTracking(env.createElement(tag));
      },
      getElementById: env.document.getElementById.bind(env.document),
      querySelector: env.document.querySelector.bind(env.document),
      querySelectorAll: env.document.querySelectorAll.bind(env.document),
      addEventListener: env.document.addEventListener.bind(env.document),
      removeEventListener: env.document.removeEventListener.bind(env.document),
      dispatchEvent: env.document.dispatchEvent.bind(env.document),
    };

    for (const el of [
      btnRegen, refreshIcon, lockWrapper, budgetTierBadge, budgetInput, peopleSelect,
      budgetSubtext, basketVal, basketSubtitle, bufferVal, checkedCounter, checkedSpend,
      postcodeInput, btnSavePostcode, finderLink, mapsLink, loadingEl, contentEl,
      tabShopping, panelShopping, btnUncheck, shoppingCategories, daysGrid, recipesContainer,
      tabItemCount, tabRecipeCount, planTitle, nutritionHighlight,
    ]) {
      container.appendChild(el);
    }
    env.document.body.appendChild(container);
    env.localStorage.setItem('nommon_user_postcode', 'EC1A 1BB');

    const nommonSrc = fs.readFileSync(path.join(ROOT_DIR, 'public/javascripts/nommon.js'), 'utf8');
    vm.runInNewContext(nommonSrc, {
      window: env.window,
      document: patchedDocument,
      navigator: env.window.navigator,
      localStorage: env.localStorage,
      fetch: env.window.fetch,
      Set,
      Array,
      Object,
      JSON,
      Math,
      Number,
      String,
      RegExp,
      Date,
      Error,
      parseFloat,
      parseInt,
      isNaN,
      encodeURIComponent,
      confirm: () => true,
      alert() {},
      console: { log() {}, warn() {}, error() {} },
    });

    // Enter offline before DOMContentLoaded so updateStoreLinks skips api.postcodes.io
    env.window.monmonOfflineMode.enterOffline();
    env.document.dispatchEvent(env.makeEvent('DOMContentLoaded'));
    await env.timers.flushMicrotasks();

    assert.equal(
      env.fetchCalls.some((c) => String(c.input).includes('api.postcodes.io')),
      false,
      'Must skip api.postcodes.io lookup while offline'
    );
    assert.match(finderLink.href, /google\.com\/maps\/search/);
    assert.match(basketSubtitle.innerHTML, /Cached snapshot/);
    assert.equal(btnRegen.disabled, true);
    assert.equal(budgetInput.disabled, true);
    assert.equal(peopleSelect.disabled, true);
    assert.equal(btnSavePostcode.disabled, true);
    const unlockBtn = env.document.getElementById('btn-unlock-nommon');
    assert.ok(unlockBtn, 'Expected unlock button rendered');
    assert.equal(unlockBtn.disabled, true);
    assert.equal(btnUncheck.disabled, false);
    assert.equal(tabShopping.disabled, false);

    // Force-regenerate while offline shows toast and makes zero extra fetch calls
    const beforeForceCount = env.fetchCalls.length;
    env.elements.offlineToast.style.display = 'none';
    btnRegen.dispatchEvent(env.makeEvent('click', { bubbles: true, cancelable: true }));
    await env.timers.flushMicrotasks();
    assert.equal(env.fetchCalls.length, beforeForceCount);

    // Coming back online re-enables controls
    env.window.monmonOfflineMode.exitOffline();
    assert.equal(btnRegen.disabled, false);
    assert.equal(budgetInput.disabled, false);
    assert.equal(peopleSelect.disabled, false);
    assert.equal(btnSavePostcode.disabled, false);
    assert.equal(unlockBtn.disabled, false);
  });

  it('15. [Task 6] nav_header.html global loader returns early on e.defaultPrevented and offline forms/edit links', () => {
    const navHeaderHtml = fs.readFileSync(path.join(ROOT_DIR, 'modules/monmon-rs/templates/nav_header.html'), 'utf8');
    // Extract the loader <script> block right after #monmon-page-loader styles
    const match = navHeaderHtml.match(/<script>\s*\(function\(\)\s*\{\s*var loader = document\.getElementById\('monmon-page-loader'\);[\s\S]*?\}\)\(\);\s*<\/script>/);
    assert.ok(match, 'Could not locate global loader script in nav_header.html');
    const loaderScript = match[0].replace(/^<script>/, '').replace(/<\/script>$/, '');

    const env = createEnv({ buildHash: 'v1', readyState: 'complete' });
    const loaderEl = env.createElement('div', { id: 'monmon-page-loader', classes: ['monmon-loader-overlay'] });
    const loaderText = env.createElement('div', { id: 'monmon-loader-text', text: 'Loading...' });
    const loaderSub = env.createElement('div', { classes: ['monmon-loader-subtext'], text: 'Crunching' });
    const closeBtn = env.createElement('button', { id: 'monmon-loader-close' });
    loaderEl.appendChild(closeBtn);
    loaderEl.appendChild(loaderText);
    loaderEl.appendChild(loaderSub);
    env.document.body.appendChild(loaderEl);

    const editLink = env.createElement('a', { attrs: { href: '/editOutgoing/12' } });
    const normalForm = env.createElement('form', { attrs: { method: 'POST', action: '/outgoings/save' } });
    env.document.body.appendChild(editLink);
    env.document.body.appendChild(normalForm);

    vm.runInNewContext(loaderScript, {
      window: env.window,
      document: env.document,
      URL,
      setTimeout: env.window.setTimeout,
      clearTimeout: env.window.clearTimeout,
    });

    // Offline: clicking edit link or submitting form must NOT activate loader
    env.window.monmonOfflineMode.enterOffline();
    editLink.dispatchEvent(env.makeEvent('click', { bubbles: true, cancelable: true }));
    assert.equal(loaderEl.classList.contains('is-active'), false);

    normalForm.dispatchEvent(env.makeEvent('submit', { bubbles: true, cancelable: true }));
    assert.equal(loaderEl.classList.contains('is-active'), false);

    // Online but defaultPrevented: must NOT activate loader
    env.window.monmonOfflineMode.exitOffline();
    const preventedSubmit = env.makeEvent('submit', { bubbles: true, cancelable: true });
    preventedSubmit.preventDefault();
    normalForm.dispatchEvent(preventedSubmit);
    assert.equal(loaderEl.classList.contains('is-active'), false);
  });

  it('16. [Task 6] nommon.js shows "Offline — no cached meal plan found" on offline cache-miss and transfers.js stays 100% interactive offline', async () => {
    // 16a. NomMon offline cache-miss (503 { offline: true })
    const envNommonMiss = createEnv({
      buildHash: 'v1',
      readyState: 'loading',
      onLine: false,
      fetchImpl: async (url) => {
        if (String(url).startsWith('/api/nommon/plan')) {
          return new Response(
            JSON.stringify({ offline: true, error: 'Offline — no cached data available.' }),
            { status: 503, headers: { 'Content-Type': 'application/json' } }
          );
        }
        return new Response(JSON.stringify({ buildHash: 'v1' }), { status: 200 });
      },
    });
    const container = envNommonMiss.createElement('div', { id: 'nommon-container', attrs: { 'data-budget': '50', 'data-configured': 'true' } });
    const loadingEl = envNommonMiss.createElement('div', { id: 'nommon-loading' });
    const contentEl = envNommonMiss.createElement('div', { id: 'nommon-content' });
    const postcodeInput = envNommonMiss.createElement('input', { id: 'nommon-postcode-input' });
    postcodeInput.value = '';
    container.appendChild(loadingEl);
    container.appendChild(contentEl);
    container.appendChild(postcodeInput);
    envNommonMiss.document.body.appendChild(container);

    const nommonSrc = fs.readFileSync(path.join(ROOT_DIR, 'public/javascripts/nommon.js'), 'utf8');
    vm.runInNewContext(nommonSrc, {
      window: envNommonMiss.window,
      document: envNommonMiss.document,
      navigator: envNommonMiss.window.navigator,
      localStorage: envNommonMiss.localStorage,
      fetch: envNommonMiss.window.fetch,
      Set, Array, Object, JSON, Math, Number, String, RegExp, Date, Error,
      parseFloat, parseInt, isNaN, encodeURIComponent,
      confirm: () => true, alert() {}, console: { log() {}, warn() {}, error() {} },
    });
    envNommonMiss.document.dispatchEvent(envNommonMiss.makeEvent('DOMContentLoaded'));
    await envNommonMiss.timers.flushMicrotasks();
    assert.match(loadingEl.innerHTML, /Offline — no cached meal plan found/);

    // 16b. transfers.js checkboxes, Clear Checks, Copy, breakdown toggle, and Next/Last tabs offline
    const envTransfers = createEnv({ buildHash: 'v1', readyState: 'loading', onLine: false });
    const tabBtnNext = envTransfers.createElement('button', { id: 'tab-btn-next', classes: ['tab-btn', 'active'], attrs: { type: 'button', 'data-offline-allowed': 'true' } });
    const tabBtnLast = envTransfers.createElement('button', { id: 'tab-btn-last', classes: ['tab-btn'], attrs: { type: 'button', 'data-offline-allowed': 'true' } });
    const panelNext = envTransfers.createElement('div', { id: 'panel-next' });
    const panelLast = envTransfers.createElement('div', { id: 'panel-last' });
    const cardsNext = envTransfers.createElement('div', { id: 'transfer-cards-container-next' });
    const cardsLast = envTransfers.createElement('div', { id: 'transfer-cards-container-last' });
    const clearNextBtn = envTransfers.createElement('button', { id: 'next-clear-btn', classes: ['btn-clear-checks'], attrs: { type: 'button', 'data-offline-allowed': 'true' } });
    const summaryNext = envTransfers.createElement('span', { id: 'next-checked-summary' });
    const card1 = envTransfers.createElement('div', { id: 'card-1', classes: ['transfer-card'], attrs: { 'data-window': 'next', 'data-item-id': '1' } });
    const chk1 = envTransfers.createElement('input', { id: 'chk-next-1', attrs: { type: 'checkbox' } });
    chk1.checked = false;
    const breakdown = envTransfers.createElement('div', { id: 'breakdown-next-1' });
    breakdown.style.display = 'none';
    const copyBtn = envTransfers.createElement('button', { classes: ['btn-copy-amt'], attrs: { type: 'button', 'data-offline-allowed': 'true' } });
    copyBtn.innerHTML = 'Copy';

    card1.appendChild(chk1);
    card1.appendChild(breakdown);
    card1.appendChild(copyBtn);
    cardsNext.appendChild(card1);
    panelNext.appendChild(clearNextBtn);
    panelNext.appendChild(summaryNext);
    panelNext.appendChild(cardsNext);
    panelLast.appendChild(cardsLast);
    envTransfers.document.body.appendChild(tabBtnNext);
    envTransfers.document.body.appendChild(tabBtnLast);
    envTransfers.document.body.appendChild(panelNext);
    envTransfers.document.body.appendChild(panelLast);

    let copiedText = '';
    const navForTransfers = {
      ...envTransfers.window.navigator,
      clipboard: {
        async writeText(txt) { copiedText = txt; },
      },
    };
    const transfersSrc = fs.readFileSync(path.join(ROOT_DIR, 'public/javascripts/transfers.js'), 'utf8');
    vm.runInNewContext(transfersSrc, {
      window: envTransfers.window,
      document: envTransfers.document,
      navigator: navForTransfers,
      localStorage: envTransfers.localStorage,
      URL,
      setTimeout: envTransfers.window.setTimeout,
      clearTimeout: envTransfers.window.clearTimeout,
      console: { log() {}, warn() {}, error() {} },
    });
    envTransfers.document.dispatchEvent(envTransfers.makeEvent('DOMContentLoaded'));

    assert.equal(envTransfers.window.monmonIsOffline(), true);
    // Toggle checkbox via card click
    card1.dispatchEvent(envTransfers.makeEvent('click', { bubbles: true, cancelable: true }));
    assert.equal(chk1.checked, true);
    assert.equal(envTransfers.localStorage.getItem('monmon_payday_transfer_done_next_1'), 'true');

    // Toggle breakdown
    envTransfers.window.toggleBreakdown('next-1');
    assert.equal(breakdown.style.display, 'block');

    // Switch tab
    envTransfers.window.switchWindowTab('last');
    assert.equal(panelLast.style.display, 'block');
    assert.equal(panelNext.style.display, 'none');

    // Copy amount
    envTransfers.window.copyTransferAmount('125.00', copyBtn);
    await envTransfers.timers.flushMicrotasks();
    assert.equal(copiedText, '125.00');

    // Clear all checks
    envTransfers.localStorage.key = (idx) => Array.from(envTransfers.localStorage._map.keys())[idx] ?? null;
    Object.defineProperty(envTransfers.localStorage, 'length', { get: () => envTransfers.localStorage._map.size });
    envTransfers.window.clearAllChecks('next');
    assert.equal(chk1.checked, false);
    assert.equal(envTransfers.localStorage.getItem('monmon_payday_transfer_done_next_1'), null);
  });
});
