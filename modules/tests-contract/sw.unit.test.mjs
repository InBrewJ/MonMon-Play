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
const SW_PATH = path.join(ROOT_DIR, 'public/javascripts/sw.js');

const ORIGIN = 'https://monmon.test';
const TEST_BUILD_HASH = 'abc1234';

function createFakeTimers() {
  let now = 0;
  let nextId = 1;
  const timers = new Map();

  function setTimer(fn, delay = 0) {
    const id = nextId++;
    const ms = Math.max(0, Number(delay) || 0);
    timers.set(id, { id, runAt: now + ms, fn });
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
        if (entry.runAt <= target && (!earliest || entry.runAt < earliest.runAt || (entry.runAt === earliest.runAt && entry.id < earliest.id))) {
          earliest = entry;
        }
      }
      if (!earliest) break;
      timers.delete(earliest.id);
      now = earliest.runAt;
      earliest.fn();
      await flushMicrotasks();
    }
    now = target;
    await flushMicrotasks();
  }

  function clearAll() {
    timers.clear();
  }

  return { setTimer, clearTimer, tick, flushMicrotasks, clearAll, getNow: () => now };
}

function makeResponse(body, init = {}) {
  const { url, redirected, type, statusOverride, ...respInit } = init;
  const resp = new Response(body, respInit);
  if (url !== undefined) {
    Object.defineProperty(resp, 'url', { value: url, configurable: true });
  }
  if (redirected !== undefined) {
    Object.defineProperty(resp, 'redirected', { value: Boolean(redirected), configurable: true });
  }
  if (type !== undefined) {
    Object.defineProperty(resp, 'type', { value: type, configurable: true });
  }
  if (statusOverride !== undefined) {
    Object.defineProperty(resp, 'status', { value: statusOverride, configurable: true });
    Object.defineProperty(resp, 'ok', { value: statusOverride >= 200 && statusOverride < 300, configurable: true });
  }
  return resp;
}

function createCacheStorage() {
  const store = new Map();

  function normalizeKey(req) {
    const raw = typeof req === 'string' ? req : req.url;
    return new URL(raw, ORIGIN).href;
  }

  function wrapCache(map) {
    return {
      async put(req, res) {
        map.set(normalizeKey(req), res.clone());
      },
      async match(req) {
        const hit = map.get(normalizeKey(req));
        return hit ? hit.clone() : undefined;
      },
      async delete(req) {
        return map.delete(normalizeKey(req));
      },
      async keys() {
        return Array.from(map.keys()).map((u) => new Request(u));
      },
      _rawMap: map,
    };
  }

  return {
    async open(name) {
      if (!store.has(name)) {
        store.set(name, new Map());
      }
      return wrapCache(store.get(name));
    },
    async keys() {
      return Array.from(store.keys());
    },
    async delete(name) {
      return store.delete(name);
    },
    async has(name) {
      return store.has(name);
    },
    async match(req) {
      for (const map of store.values()) {
        const hit = map.get(normalizeKey(req));
        if (hit) return hit.clone();
      }
      return undefined;
    },
    _store: store,
  };
}

function loadServiceWorker({ fetchImpl, timeoutOverride } = {}) {
  const rawSource = fs.readFileSync(SW_PATH, 'utf8');
  const source = rawSource.replaceAll('__MONMON_BUILD_HASH__', TEST_BUILD_HASH);

  const timers = createFakeTimers();
  const caches = createCacheStorage();
  const listeners = new Map();
  const postedMessages = [];
  let skipWaitingCalled = false;
  let claimed = false;

  const mockClient = {
    id: 'win-1',
    type: 'window',
    postMessage(msg) {
      postedMessages.push(structuredClone(msg));
    },
  };

  const defaultFetch = async () => makeResponse('ok', { status: 200 });
  let activeFetch = fetchImpl || defaultFetch;

  const context = {
    URL,
    Headers,
    Request,
    Response,
    AbortController,
    AbortSignal,
    Date,
    Promise,
    Set,
    Map,
    Array,
    Object,
    JSON,
    Math,
    Error,
    TypeError,
    RegExp,
    console,
    setTimeout: timers.setTimer,
    clearTimeout: timers.clearTimer,
    caches,
    location: new URL(`${ORIGIN}/sw.js`),
    clients: {
      async matchAll() {
        return [mockClient];
      },
      async claim() {
        claimed = true;
      },
    },
    async skipWaiting() {
      skipWaitingCalled = true;
    },
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    fetch(input, init) {
      return activeFetch(input, init);
    },
  };
  if (typeof timeoutOverride === 'number') {
    context.__MONMON_TIMEOUT_MS = timeoutOverride;
  }
  context.self = context;

  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'sw.js' });

  async function dispatchLifecycle(type) {
    const fns = listeners.get(type) || [];
    assert.ok(fns.length > 0, `Expected '${type}' listener to be registered`);
    const waitUntilPromises = [];
    const event = {
      waitUntil(p) {
        waitUntilPromises.push(Promise.resolve(p));
      },
    };
    for (const fn of fns) {
      fn(event);
    }
    await Promise.all(waitUntilPromises);
  }

  function dispatchFetch(request) {
    const fns = listeners.get('fetch') || [];
    assert.ok(fns.length > 0, "Expected 'fetch' listener to be registered");
    let responsePromise = null;
    const waitUntilPromises = [];
    const event = {
      request,
      respondWith(p) {
        responsePromise = Promise.resolve(p);
      },
      waitUntil(p) {
        waitUntilPromises.push(Promise.resolve(p));
      },
    };
    for (const fn of fns) {
      fn(event);
    }
    return {
      intercepted: responsePromise !== null,
      responsePromise,
      waitUntilPromises,
    };
  }

  async function dispatchMessage(data) {
    const fns = listeners.get('message') || [];
    assert.ok(fns.length > 0, "Expected 'message' listener to be registered");
    const waitUntilPromises = [];
    const replyMessages = [];
    const event = {
      data,
      source: mockClient,
      ports: [
        {
          postMessage(msg) {
            replyMessages.push(msg);
          },
        },
      ],
      waitUntil(p) {
        waitUntilPromises.push(Promise.resolve(p));
      },
    };
    for (const fn of fns) {
      fn(event);
    }
    await Promise.all(waitUntilPromises);
    return { replyMessages };
  }

  return {
    context,
    caches,
    timers,
    postedMessages,
    mockClient,
    setFetch(fn) {
      activeFetch = fn;
    },
    getSkipWaitingCalled: () => skipWaitingCalled,
    getClaimed: () => claimed,
    dispatchLifecycle,
    dispatchFetch,
    dispatchMessage,
    cleanup() {
      timers.clearAll();
    },
  };
}

let activeHarness = null;
afterEach(() => {
  if (activeHarness) {
    activeHarness.cleanup();
    activeHarness = null;
  }
});

describe('Service Worker (public/javascripts/sw.js)', () => {
  it('1. install precaches static vendor assets, 7 Latin/FA woff2 fonts, hashed JS/CSS, and /offline into monmon-static-abc1234 and calls skipWaiting()', async () => {
    const fetchedUrls = [];
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async (input) => {
        const url = typeof input === 'string' ? input : input.url;
        fetchedUrls.push(url);
        // Simulate one optional icon missing (404) to verify resilient precaching
        if (url.endsWith('/assets/images/favicon.png')) {
          return makeResponse('Not Found', { status: 404 });
        }
        return makeResponse(`asset:${url}`, { status: 200 });
      },
    }));

    await sw.dispatchLifecycle('install');

    assert.equal(sw.getSkipWaitingCalled(), true, 'skipWaiting() should be called during install');
    const staticCache = await sw.caches.open(`monmon-static-${TEST_BUILD_HASH}`);

    const requiredPrecachePaths = [
      '/offline',
      `/assets/vendor/w3css/w3.css?v=${TEST_BUILD_HASH}`,
      `/assets/vendor/fontawesome/css/all.min.css?v=${TEST_BUILD_HASH}`,
      `/assets/vendor/fonts/fonts.css?v=${TEST_BUILD_HASH}`,
      `/assets/vendor/leaflet/leaflet.css?v=${TEST_BUILD_HASH}`,
      `/assets/vendor/leaflet/leaflet.js?v=${TEST_BUILD_HASH}`,
      '/assets/vendor/fonts/inter-latin.woff2',
      '/assets/vendor/fonts/inter-latin-ext.woff2',
      '/assets/vendor/fonts/plus-jakarta-sans-latin.woff2',
      '/assets/vendor/fonts/plus-jakarta-sans-latin-ext.woff2',
      '/assets/vendor/fontawesome/webfonts/fa-solid-900.woff2',
      '/assets/vendor/fontawesome/webfonts/fa-regular-400.woff2',
      '/assets/vendor/fontawesome/webfonts/fa-brands-400.woff2',
      `/assets/stylesheets/main.${TEST_BUILD_HASH}.css`,
      `/assets/javascripts/common.${TEST_BUILD_HASH}.js`,
      `/assets/javascripts/offline-mode.${TEST_BUILD_HASH}.js`,
      `/assets/javascripts/nommon.${TEST_BUILD_HASH}.js`,
      `/assets/javascripts/transfers.${TEST_BUILD_HASH}.js`,
      '/assets/images/monmon-monster.svg',
      '/assets/images/favicon.svg',
      '/assets/site.webmanifest',
    ];

    for (const p of requiredPrecachePaths) {
      const hit = await staticCache.match(`${ORIGIN}${p}`);
      assert.ok(hit, `Expected ${p} to be precached in monmon-static-${TEST_BUILD_HASH}`);
    }
    assert.equal(await staticCache.match(`${ORIGIN}/assets/images/favicon.png`), undefined, '404 optional icon should not be stored');
    assert.ok(fetchedUrls.length >= requiredPrecachePaths.length);

    // Core /offline failure MUST fail install
    const failSw = (activeHarness = loadServiceWorker({
      fetchImpl: async (input) => {
        const url = typeof input === 'string' ? input : input.url;
        if (url === `${ORIGIN}/offline`) {
          return makeResponse('Server Error', { status: 500 });
        }
        return makeResponse('ok', { status: 200 });
      },
    }));
    await assert.rejects(() => failSw.dispatchLifecycle('install'));
  });

  it('2. activate deletes stale monmon-static-old0000 and monmon-pages-old0000 caches, keeps *-abc1234, and calls clients.claim()', async () => {
    const sw = (activeHarness = loadServiceWorker());

    const oldStatic = await sw.caches.open('monmon-static-old0000');
    await oldStatic.put(`${ORIGIN}/offline`, makeResponse('old'));
    const oldPages = await sw.caches.open('monmon-pages-old0000');
    await oldPages.put(`${ORIGIN}/spog`, makeResponse('old spog'));

    const curStatic = await sw.caches.open(`monmon-static-${TEST_BUILD_HASH}`);
    await curStatic.put(`${ORIGIN}/offline`, makeResponse('current'));
    const curPages = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    await curPages.put(`${ORIGIN}/spog`, makeResponse('current spog'));

    await sw.dispatchLifecycle('activate');

    assert.equal(sw.getClaimed(), true, 'clients.claim() should be called on activate');
    assert.equal(await sw.caches.has('monmon-static-old0000'), false);
    assert.equal(await sw.caches.has('monmon-pages-old0000'), false);
    assert.equal(await sw.caches.has(`monmon-static-${TEST_BUILD_HASH}`), true);
    assert.equal(await sw.caches.has(`monmon-pages-${TEST_BUILD_HASH}`), true);
  });

  it('3. non-GET (POST, PUT, DELETE) returns synthetic 503 JSON { offline: true, ... } when fetch rejects', async () => {
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async () => {
        throw new TypeError('NetworkError when attempting to fetch resource');
      },
    }));

    for (const method of ['POST', 'PUT', 'DELETE']) {
      const req = new Request(`${ORIGIN}/binOutgoing/42`, { method });
      const { intercepted, responsePromise } = sw.dispatchFetch(req);
      assert.equal(intercepted, true, `${method} request should be intercepted for offline error handling`);
      const resp = await responsePromise;
      assert.equal(resp.status, 503);
      assert.match(resp.headers.get('content-type') || '', /application\/json/i);
      const body = await resp.json();
      assert.equal(body.offline, true);
      assert.equal(typeof body.error, 'string');
    }
  });

  it('4. static /assets/... uses Cache-First without calling fetch, and Network-Only endpoints are bypassed', async () => {
    let fetchCalls = 0;
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async () => {
        fetchCalls++;
        return makeResponse('network');
      },
    }));

    const staticCache = await sw.caches.open(`monmon-static-${TEST_BUILD_HASH}`);
    await staticCache.put(
      `${ORIGIN}/assets/vendor/fonts/inter-latin.woff2`,
      makeResponse('cached-woff2', { status: 200, headers: { 'Content-Type': 'font/woff2' } })
    );

    const staticResult = sw.dispatchFetch(new Request(`${ORIGIN}/assets/vendor/fonts/inter-latin.woff2`));
    assert.equal(staticResult.intercepted, true);
    const staticResp = await staticResult.responsePromise;
    assert.equal(await staticResp.text(), 'cached-woff2');
    assert.equal(fetchCalls, 0, 'Cache-First hit must not invoke fetch');

    for (const networkOnlyUrl of [
      `${ORIGIN}/api/version`,
      `${ORIGIN}/api/search?q=coffee`,
      `${ORIGIN}/login`,
      `${ORIGIN}/centralLogout`,
      'https://api.postcodes.io/postcodes/EH52',
    ]) {
      const res = sw.dispatchFetch(new Request(networkOnlyUrl));
      assert.equal(res.intercepted, false, `Expected ${networkOnlyUrl} to bypass SW interception`);
    }
  });

  it('5. HTML navigation Lie-Fi timeout at 2500ms returns cached HTML with X-MonMon-Offline: 1, X-MonMon-Cached-At, posts MONMON_OFFLINE_SERVED, and refreshes in background', async () => {
    const cachedAt = '2026-11-19T09:15:00.000Z';
    const sw = (activeHarness = loadServiceWorker());

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    await pagesCache.put(
      `${ORIGIN}/spog`,
      makeResponse('<html>cached-spog</html>', {
        status: 200,
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'X-MonMon-Cached-At': cachedAt,
        },
      })
    );

    let resolveLiveFetch;
    sw.setFetch(
      () =>
        new Promise((resolve) => {
          resolveLiveFetch = resolve;
        })
    );

    const req = new Request(`${ORIGIN}/spog`, {
      headers: { Accept: 'text/html' },
    });
    const { intercepted, responsePromise, waitUntilPromises } = sw.dispatchFetch(req);
    assert.equal(intercepted, true);

    let settled = false;
    responsePromise.then(() => {
      settled = true;
    });

    await sw.timers.tick(2499);
    assert.equal(settled, false, 'Must wait full 2500ms Lie-Fi window before falling back to cache');

    await sw.timers.tick(1);
    assert.equal(settled, true, 'Must resolve with cached HTML at 2500ms');

    const resp = await responsePromise;
    assert.equal(resp.status, 200);
    assert.equal(await resp.text(), '<html>cached-spog</html>');
    assert.equal(resp.headers.get('X-MonMon-Offline'), '1');
    assert.equal(resp.headers.get('X-MonMon-Cached-At'), cachedAt);

    assert.deepEqual(sw.postedMessages, [
      { type: 'MONMON_OFFLINE_SERVED', url: '/spog', cachedAt },
    ]);

    // Live fetch finishes at 4s and silently refreshes cache
    resolveLiveFetch(
      makeResponse('<html>fresh-spog</html>', {
        status: 200,
        url: `${ORIGIN}/spog`,
        headers: { 'Content-Type': 'text/html; charset=utf-8' },
      })
    );
    await Promise.all(waitUntilPromises);

    const refreshed = await pagesCache.match(`${ORIGIN}/spog`);
    assert.equal(await refreshed.text(), '<html>fresh-spog</html>');
    assert.ok(refreshed.headers.get('X-MonMon-Cached-At'));

    // Also verify hanging Lie-Fi fetch > BG_ABORT_MS (10000ms) aborts its AbortSignal
    let hangingSignal = null;
    sw.setFetch((input) => {
      hangingSignal = input.signal;
      return new Promise(() => {});
    });
    const hangingRes = sw.dispatchFetch(new Request(`${ORIGIN}/spog`, { headers: { Accept: 'text/html' } }));
    await sw.timers.tick(2500);
    await hangingRes.responsePromise;
    assert.equal(hangingSignal.aborted, false, 'Must keep in-flight HTML request alive past 2.5s for background refresh');
    await sw.timers.tick(7499);
    assert.equal(hangingSignal.aborted, false, 'Must wait full 10000ms BG_ABORT_MS before aborting');
    await sw.timers.tick(1);
    assert.equal(hangingSignal.aborted, true, 'Must abort hanging background HTML fetch at 10000ms');
  });

  it('6. HTML navigation cache miss when offline returns cached /offline, and fast JSON uses 2500ms Lie-Fi fallback', async () => {
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async () => {
        throw new TypeError('Offline');
      },
    }));

    const staticCache = await sw.caches.open(`monmon-static-${TEST_BUILD_HASH}`);
    await staticCache.put(
      `${ORIGIN}/offline`,
      makeResponse('<html><body>You are offline</body></html>', {
        status: 200,
        headers: { 'Content-Type': 'text/html; charset=utf-8' },
      })
    );

    const navResult = sw.dispatchFetch(
      new Request(`${ORIGIN}/myAccounts`, { headers: { Accept: 'text/html' } })
    );
    const navResp = await navResult.responsePromise;
    assert.equal(navResp.status, 200);
    assert.match(await navResp.text(), /You are offline/);

    // Fast JSON (/api/spog/metrics) cached fallback on rejection
    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    await pagesCache.put(
      `${ORIGIN}/api/spog/metrics`,
      makeResponse(JSON.stringify({ dailyAllowance: 25.5 }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'X-MonMon-Cached-At': '2026-11-19T08:00:00.000Z',
        },
      })
    );
    const jsonResult = sw.dispatchFetch(new Request(`${ORIGIN}/api/spog/metrics`));
    const jsonResp = await jsonResult.responsePromise;
    assert.equal(jsonResp.headers.get('X-MonMon-Offline'), '1');
    assert.equal(jsonResp.headers.get('X-MonMon-Cached-At'), '2026-11-19T08:00:00.000Z');
    assert.deepEqual(await jsonResp.json(), { dailyAllowance: 25.5 });
    assert.equal(sw.postedMessages.some((m) => m.type === 'MONMON_OFFLINE_SERVED' && m.url === '/api/spog/metrics'), true);
  });

  it('7. HTML response with X-MonMon-Time-Travel: 1 is returned live but NEVER stored in monmon-pages-abc1234', async () => {
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async () =>
        makeResponse('<html>time-travel-2023</html>', {
          status: 200,
          url: `${ORIGIN}/spog`,
          headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'X-MonMon-Time-Travel': '1',
          },
        }),
    }));

    const res = sw.dispatchFetch(new Request(`${ORIGIN}/spog`, { headers: { Accept: 'text/html' } }));
    const resp = await res.responsePromise;
    assert.equal(await resp.text(), '<html>time-travel-2023</html>');
    assert.equal(resp.headers.get('X-MonMon-Time-Travel'), '1');

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    assert.equal(await pagesCache.match(`${ORIGIN}/spog`), undefined, 'Time Travel HTML must never be cached');
  });

  it('8. [Review Focus #3] HTML response redirected to /login is NEVER cached and aborts MONMON_PREWARM with MONMON_PREWARM_ABORTED', async () => {
    const prewarmFetched = [];
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async (input) => {
        const reqUrl = typeof input === 'string' ? input : input.url;
        prewarmFetched.push(reqUrl);
        return makeResponse('<html>Login page</html>', {
          status: 200,
          url: `${ORIGIN}/login?return_to=%2Fspog`,
          redirected: true,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        });
      },
    }));

    const navRes = sw.dispatchFetch(new Request(`${ORIGIN}/spog`, { headers: { Accept: 'text/html' } }));
    const navResp = await navRes.responsePromise;
    assert.equal(await navResp.text(), '<html>Login page</html>');

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    assert.equal(await pagesCache.match(`${ORIGIN}/spog`), undefined, 'Redirected /login response must not poison /spog cache');

    // Now verify MONMON_PREWARM aborts immediately on /login redirect without fetching later URLs
    prewarmFetched.length = 0;
    let firstSawRedirect = false;
    sw.setFetch(async (input) => {
      const reqUrl = typeof input === 'string' ? input : input.url;
      prewarmFetched.push(reqUrl);
      if (reqUrl.endsWith('/spog')) {
        firstSawRedirect = true;
        return makeResponse('<html>Login</html>', {
          status: 200,
          url: `${ORIGIN}/login`,
          redirected: true,
          headers: { 'Content-Type': 'text/html' },
        });
      }
      // Keep second concurrent request slightly slower so queue items 3..4 should never start
      await new Promise((r) => sw.timers.setTimer(r, 50));
      return makeResponse('<html>ok</html>', {
        status: 200,
        url: reqUrl,
        headers: { 'Content-Type': 'text/html' },
      });
    });

    const prewarmDone = sw.dispatchMessage({
      type: 'MONMON_PREWARM',
      urls: ['/spog', '/nommon', '/myOutgoings', '/myAccounts', '/transfers'],
    });
    await sw.timers.tick(100);
    await prewarmDone;

    assert.equal(firstSawRedirect, true);
    assert.equal(await pagesCache.match(`${ORIGIN}/spog`), undefined);
    assert.ok(
      sw.postedMessages.some((m) => m.type === 'MONMON_PREWARM_ABORTED'),
      'Expected MONMON_PREWARM_ABORTED to be posted'
    );
    assert.equal(sw.postedMessages.some((m) => m.type === 'MONMON_PREWARM_DONE'), false);
    assert.ok(
      !prewarmFetched.includes(`${ORIGIN}/myOutgoings`) &&
        !prewarmFetched.includes(`${ORIGIN}/myAccounts`) &&
        !prewarmFetched.includes(`${ORIGIN}/transfers`),
      `Remaining URLs should not be fetched after abort, got: ${prewarmFetched.join(', ')}`
    );
  });

  it('9. [Review Focus #2] Mistral AI endpoint handles slow cache miss without aborting, slow cache hit with X-MonMon-Cached-Fallback: 1 + background refresh, and ?force=true cache bypass', async () => {
    const sw = (activeHarness = loadServiceWorker());
    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);

    // 9a: Cache miss with 12s slow response (>2.5s and >10s) must NOT abort and must NOT post MONMON_OFFLINE_SERVED
    let seenSignal = null;
    let resolveSlowMiss;
    sw.setFetch((input) => {
      seenSignal = input.signal;
      return new Promise((resolve) => {
        resolveSlowMiss = resolve;
      });
    });

    const missFetch = sw.dispatchFetch(new Request(`${ORIGIN}/api/ed-says`));
    let missSettled = false;
    missFetch.responsePromise.then(() => {
      missSettled = true;
    });

    await sw.timers.tick(12000);
    assert.equal(missSettled, false, 'Uncached AI call must wait for live response without 2.5s or 10s cutoff');
    assert.equal(seenSignal ? seenSignal.aborted : false, false, 'Uncached AI call must never be aborted');

    resolveSlowMiss(
      makeResponse(JSON.stringify({ configured: true, edSays: 'Slow live wisdom' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    const missResp = await missFetch.responsePromise;
    assert.deepEqual(await missResp.json(), { configured: true, edSays: 'Slow live wisdom' });
    assert.equal(sw.postedMessages.length, 0, 'Slow AI cache miss must never emit MONMON_OFFLINE_SERVED');

    // 9b: Cache hit with slow response (>2.5s) returns cached copy with X-MonMon-Cached-Fallback: 1, no MONMON_OFFLINE_SERVED, and updates cache when live finishes
    let resolveSlowHit;
    sw.setFetch(
      () =>
        new Promise((resolve) => {
          resolveSlowHit = resolve;
        })
    );
    const hitFetch = sw.dispatchFetch(new Request(`${ORIGIN}/api/ed-says`));
    await sw.timers.tick(2500);
    const hitResp = await hitFetch.responsePromise;
    assert.equal(hitResp.headers.get('X-MonMon-Cached-Fallback'), '1');
    assert.equal(hitResp.headers.get('X-MonMon-Offline'), null);
    assert.ok(hitResp.headers.get('X-MonMon-Cached-At'));
    assert.deepEqual(await hitResp.json(), { configured: true, edSays: 'Slow live wisdom' });
    assert.equal(sw.postedMessages.length, 0, 'Slow AI fallback must NOT emit MONMON_OFFLINE_SERVED');

    resolveSlowHit(
      makeResponse(JSON.stringify({ configured: true, edSays: 'Updated live wisdom' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    await Promise.all(hitFetch.waitUntilPromises);
    const updatedCached = await pagesCache.match(`${ORIGIN}/api/ed-says`);
    assert.deepEqual(await updatedCached.json(), { configured: true, edSays: 'Updated live wisdom' });

    // 9c: ?force=true bypasses cache completely (even when cache has an entry and network rejects or is slow)
    let resolveForce;
    sw.setFetch(
      () =>
        new Promise((resolve) => {
          resolveForce = resolve;
        })
    );
    const forceFetch = sw.dispatchFetch(new Request(`${ORIGIN}/api/ed-says?force=true`));
    let forceSettled = false;
    forceFetch.responsePromise.then(() => {
      forceSettled = true;
    });
    await sw.timers.tick(5000);
    assert.equal(forceSettled, false, '?force=true must never serve cached copy at 2.5s');
    resolveForce(
      makeResponse(JSON.stringify({ configured: true, edSays: 'Forced fresh' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    const forceResp = await forceFetch.responsePromise;
    assert.deepEqual(await forceResp.json(), { configured: true, edSays: 'Forced fresh' });

    sw.setFetch(async () => {
      throw new TypeError('Offline');
    });
    const offlineForce = sw.dispatchFetch(new Request(`${ORIGIN}/api/ed-says?force=true`));
    const offlineForceResp = await offlineForce.responsePromise;
    assert.equal(offlineForceResp.status, 503);
    assert.equal((await offlineForceResp.json()).offline, true);
  });

  it('10. [Review Focus #4] /api/nommon/plan canonicalizes cache key across query params and never overwrites with { plan: null } from lockedOnly=true', async () => {
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async () =>
        makeResponse(
          JSON.stringify({
            configured: true,
            locked: false,
            plan: { planTitle: 'Batch Curry Week', shoppingList: [] },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        ),
    }));

    // Online load with budget=50&people=1 caches under canonical /api/nommon/plan
    const first = sw.dispatchFetch(new Request(`${ORIGIN}/api/nommon/plan?budget=50&people=1`));
    const firstResp = await first.responsePromise;
    assert.equal((await firstResp.json()).plan.planTitle, 'Batch Curry Week');

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    const canonicalEntry = await pagesCache.match(`${ORIGIN}/api/nommon/plan`);
    assert.ok(canonicalEntry, 'Plan must be stored under canonical /api/nommon/plan key');

    // Subsequent lockedOnly=true returning { plan: null } via fetch AND via MONMON_PREWARM must NOT overwrite
    sw.setFetch(async () =>
      makeResponse(JSON.stringify({ configured: true, locked: false, plan: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    const lockedCheck = sw.dispatchFetch(new Request(`${ORIGIN}/api/nommon/plan?lockedOnly=true`));
    const lockedCheckResp = await lockedCheck.responsePromise;
    assert.equal((await lockedCheckResp.json()).plan, null);

    await sw.dispatchMessage({
      type: 'MONMON_PREWARM',
      urls: ['/api/nommon/plan?lockedOnly=true'],
    });

    // Now go offline and request with different budget=60&people=2 -> must still serve Batch Curry Week
    sw.setFetch(async () => {
      throw new TypeError('Offline');
    });
    const offlineSecond = sw.dispatchFetch(new Request(`${ORIGIN}/api/nommon/plan?budget=60&people=2`));
    const offlineResp = await offlineSecond.responsePromise;
    assert.equal(offlineResp.headers.get('X-MonMon-Offline'), '1');
    const offlineData = await offlineResp.json();
    assert.equal(offlineData.plan.planTitle, 'Batch Curry Week');
  });

  it('11. MONMON_PREWARM warms with concurrency 2 and posts progress/done; MONMON_CLEAR_USER_CACHES deletes only monmon-pages-*; MONMON_PURGE_CACHES deletes all monmon-*', async () => {
    let inFlight = 0;
    let maxInFlight = 0;
    const seenHeaders = [];

    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async (input, init) => {
        inFlight++;
        if (inFlight > maxInFlight) maxInFlight = inFlight;
        const headers = new Headers((init && init.headers) || (input && input.headers));
        seenHeaders.push(headers.get('X-MonMon-Prewarm'));
        await new Promise((r) => sw.timers.setTimer(r, 20));
        inFlight--;
        const url = typeof input === 'string' ? input : input.url;
        return makeResponse(`<html>${url}</html>`, {
          status: 200,
          url,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        });
      },
    }));

    const staticCache = await sw.caches.open(`monmon-static-${TEST_BUILD_HASH}`);
    await staticCache.put(`${ORIGIN}/offline`, makeResponse('offline-page'));

    const warmUrls = ['/spog', '/nommon', '/myOutgoings', '/myAccounts', '/transfers'];
    const warmPromise = sw.dispatchMessage({ type: 'MONMON_PREWARM', urls: warmUrls });
    await sw.timers.tick(200);
    await warmPromise;

    assert.equal(maxInFlight, 2, 'MONMON_PREWARM must run with concurrency 2');
    assert.equal(seenHeaders.every((h) => h === '1'), true, 'Prewarm requests must send X-MonMon-Prewarm: 1');

    const progressMsgs = sw.postedMessages.filter((m) => m.type === 'MONMON_PREWARM_PROGRESS');
    assert.equal(progressMsgs.length, 5);
    assert.deepEqual(
      progressMsgs.map((m) => m.completed),
      [1, 2, 3, 4, 5]
    );
    const doneMsg = sw.postedMessages.find((m) => m.type === 'MONMON_PREWARM_DONE');
    assert.ok(doneMsg, 'Expected MONMON_PREWARM_DONE');
    assert.equal(doneMsg.total, 5);
    assert.ok(doneMsg.warmedAt);

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    for (const u of warmUrls) {
      assert.ok(await pagesCache.match(`${ORIGIN}${u}`), `Expected ${u} in pages cache`);
    }

    // MONMON_CLEAR_USER_CACHES deletes only monmon-pages-* and keeps monmon-static-*
    sw.postedMessages.length = 0;
    await sw.dispatchMessage({ type: 'MONMON_CLEAR_USER_CACHES' });
    assert.equal(await sw.caches.has(`monmon-pages-${TEST_BUILD_HASH}`), false);
    assert.equal(await sw.caches.has(`monmon-static-${TEST_BUILD_HASH}`), true);
    assert.ok(sw.postedMessages.some((m) => m.type === 'MONMON_USER_CACHES_CLEARED'));

    // Re-add a pages cache, then MONMON_PURGE_CACHES deletes ALL monmon-* caches
    const pages2 = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    await pages2.put(`${ORIGIN}/spog`, makeResponse('spog'));
    sw.postedMessages.length = 0;
    await sw.dispatchMessage({ type: 'MONMON_PURGE_CACHES' });
    assert.equal(await sw.caches.has(`monmon-pages-${TEST_BUILD_HASH}`), false);
    assert.equal(await sw.caches.has(`monmon-static-${TEST_BUILD_HASH}`), false);
    assert.ok(sw.postedMessages.some((m) => m.type === 'MONMON_CACHES_PURGED'));
  });

  it('12. [Critical #1] mode: "navigate" uses redirect: "manual" and mode: "same-origin", passing opaqueredirect through uncached', async () => {
    let seenFetchReq = null;
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async (input) => {
        seenFetchReq = input;
        return makeResponse('', {
          status: 200,
          statusOverride: 0,
          type: 'opaqueredirect',
          url: `${ORIGIN}/spog`,
        });
      },
    }));

    const staticCache = await sw.caches.open(`monmon-static-${TEST_BUILD_HASH}`);
    await staticCache.put(`${ORIGIN}/offline`, makeResponse('<html>offline</html>', { status: 200 }));

    // Simulate browser navigation FetchEvent.request (mode: 'navigate', redirect: 'manual')
    const navRequest = {
      url: `${ORIGIN}/spog`,
      method: 'GET',
      mode: 'navigate',
      redirect: 'manual',
      headers: new Headers({ Accept: 'text/html' }),
    };

    const { intercepted, responsePromise } = sw.dispatchFetch(navRequest);
    assert.equal(intercepted, true);
    const resp = await responsePromise;

    assert.ok(seenFetchReq, 'fetch should be called');
    assert.equal(seenFetchReq.redirect, 'manual', 'Navigate request must preserve redirect: "manual"');
    assert.equal(seenFetchReq.mode, 'same-origin', 'Same-origin navigate fetch must use mode: "same-origin"');
    assert.equal(resp.type, 'opaqueredirect', 'Must pass opaqueredirect through verbatim');
    assert.equal(resp.status, 0);

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    assert.equal(await pagesCache.match(`${ORIGIN}/spog`), undefined, 'opaqueredirect must never be cached');
  });

  it('13. [Important #1] cloneResponseWithHeaders strips Content-Encoding and Content-Length on cache write and offline fallback', async () => {
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async () =>
        makeResponse('<html><body>Decoded HTML payload</body></html>', {
          status: 200,
          url: `${ORIGIN}/spog`,
          headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'Content-Encoding': 'gzip',
            'Content-Length': '12',
          },
        }),
    }));

    // Online fetch stores decoded response in cache without Content-Encoding or compressed Content-Length
    const liveRes = sw.dispatchFetch(new Request(`${ORIGIN}/spog`, { headers: { Accept: 'text/html' } }));
    await liveRes.responsePromise;

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    const stored = await pagesCache.match(`${ORIGIN}/spog`);
    assert.ok(stored);
    assert.equal(stored.headers.get('content-encoding'), null, 'Stored response must strip Content-Encoding');
    assert.equal(stored.headers.get('content-length'), null, 'Stored response must strip Content-Length');

    // Also test offline fallback cloning a pre-existing cache entry that carries Content-Encoding/Content-Length
    await pagesCache.put(
      `${ORIGIN}/nommon`,
      makeResponse('<html><body>Cached NomMon</body></html>', {
        status: 200,
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'Content-Encoding': 'br',
          'Content-Length': '5',
          'X-MonMon-Cached-At': '2026-11-19T11:00:00.000Z',
        },
      })
    );
    sw.setFetch(async () => {
      throw new TypeError('Offline');
    });

    const fallbackRes = sw.dispatchFetch(new Request(`${ORIGIN}/nommon`, { headers: { Accept: 'text/html' } }));
    const fallbackResp = await fallbackRes.responsePromise;
    assert.equal(fallbackResp.headers.get('X-MonMon-Offline'), '1');
    assert.equal(fallbackResp.headers.get('content-encoding'), null, 'Fallback response must strip Content-Encoding');
    assert.equal(fallbackResp.headers.get('content-length'), null, 'Fallback response must strip Content-Length');
    assert.equal(await fallbackResp.text(), '<html><body>Cached NomMon</body></html>');
  });

  it('14. [Important #2] cache.put QuotaExceededError never breaks valid live responses (AI JSON, HTML navigation, static asset)', async () => {
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: async (input) => {
        const u = typeof input === 'string' ? input : input.url;
        if (u.endsWith('/api/ed-says')) {
          return makeResponse(JSON.stringify({ configured: true, edSays: 'Live despite quota' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (u.endsWith('/spog')) {
          return makeResponse('<html>Live SPOG despite quota</html>', {
            status: 200,
            url: `${ORIGIN}/spog`,
            headers: { 'Content-Type': 'text/html; charset=utf-8' },
          });
        }
        return makeResponse('body { color: #fff; }', {
          status: 200,
          headers: { 'Content-Type': 'text/css' },
        });
      },
    }));

    const origOpen = sw.caches.open.bind(sw.caches);
    sw.caches.open = async (name) => {
      const realCache = await origOpen(name);
      return {
        ...realCache,
        async put() {
          const err = new Error('Storage quota exceeded');
          err.name = 'QuotaExceededError';
          throw err;
        },
      };
    };

    const aiRes = await sw.dispatchFetch(new Request(`${ORIGIN}/api/ed-says`)).responsePromise;
    assert.equal(aiRes.status, 200);
    assert.deepEqual(await aiRes.json(), { configured: true, edSays: 'Live despite quota' });

    const htmlRes = await sw.dispatchFetch(
      new Request(`${ORIGIN}/spog`, { headers: { Accept: 'text/html' } })
    ).responsePromise;
    assert.equal(htmlRes.status, 200);
    assert.equal(await htmlRes.text(), '<html>Live SPOG despite quota</html>');

    const staticRes = await sw.dispatchFetch(
      new Request(`${ORIGIN}/assets/stylesheets/quota-test.css`)
    ).responsePromise;
    assert.equal(staticRes.status, 200);
    assert.equal(await staticRes.text(), 'body { color: #fff; }');
  });

  it('15. [Important #3] MONMON_CLEAR_USER_CACHES and MONMON_PURGE_CACHES cancel active runPrewarm so it does not repopulate caches', async () => {
    const signals = [];
    const resolvers = [];
    const sw = (activeHarness = loadServiceWorker({
      fetchImpl: (input, init) => {
        const url = typeof input === 'string' ? input : input.url;
        const signal = (init && init.signal) || (input && input.signal);
        signals.push({ url, signal });
        return new Promise((resolve) => {
          resolvers.push({ url, resolve });
        });
      },
    }));

    // Start prewarm with 4 URLs; first 2 are in flight immediately
    const prewarmTask = sw.dispatchMessage({
      type: 'MONMON_PREWARM',
      urls: ['/spog', '/nommon', '/myOutgoings', '/transfers'],
    });
    await sw.timers.flushMicrotasks();
    assert.equal(signals.length, 2, 'Expected 2 concurrent prewarm fetches in flight');
    assert.equal(signals[0].signal.aborted, false);
    assert.equal(signals[1].signal.aborted, false);

    // Send MONMON_CLEAR_USER_CACHES (logout) while prewarm is active
    await sw.dispatchMessage({ type: 'MONMON_CLEAR_USER_CACHES' });
    assert.equal(signals[0].signal.aborted, true, 'MONMON_CLEAR_USER_CACHES must abort active prewarm signal');
    assert.equal(signals[1].signal.aborted, true, 'MONMON_CLEAR_USER_CACHES must abort all active prewarm workers');

    // Even if a stubborn fetch resolves despite abort signal, it must NOT write back to monmon-pages-*
    for (const item of resolvers) {
      item.resolve(
        makeResponse(`<html>${item.url}</html>`, {
          status: 200,
          url: item.url,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        })
      );
    }
    await prewarmTask;

    const pagesCache = await sw.caches.open(`monmon-pages-${TEST_BUILD_HASH}`);
    assert.equal((await pagesCache.keys()).length, 0, 'Cancelled prewarm must not repopulate monmon-pages-* after clear');
    assert.equal(sw.postedMessages.some((m) => m.type === 'MONMON_PREWARM_DONE'), false);
    assert.equal(signals.length, 2, 'Queued URLs (/myOutgoings, /transfers) must never be fetched after cancel');

    // Also test MONMON_PURGE_CACHES cancels an active prewarm
    signals.length = 0;
    resolvers.length = 0;
    const prewarmTask2 = sw.dispatchMessage({
      type: 'MONMON_PREWARM',
      urls: ['/spog', '/nommon'],
    });
    await sw.timers.flushMicrotasks();
    assert.equal(signals.length, 2);
    await sw.dispatchMessage({ type: 'MONMON_PURGE_CACHES' });
    assert.equal(signals[0].signal.aborted, true, 'MONMON_PURGE_CACHES must abort active prewarm signal');
    for (const item of resolvers) {
      item.resolve(
        makeResponse(`<html>${item.url}</html>`, {
          status: 200,
          url: item.url,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        })
      );
    }
    await prewarmTask2;
    assert.equal(await sw.caches.has(`monmon-pages-${TEST_BUILD_HASH}`), false);
  });
});
