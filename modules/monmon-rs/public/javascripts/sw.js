/**
 * MonMon Service Worker
 * Plain JavaScript (Zero CoffeeScript).
 * Build hash token is stamped by monmon-rs GET /sw.js.
 */
'use strict';

const MONMON_BUILD_HASH = '__MONMON_BUILD_HASH__';
const STATIC_CACHE = 'monmon-static-' + MONMON_BUILD_HASH;
const PAGES_CACHE = 'monmon-pages-' + MONMON_BUILD_HASH;

const LIE_FI_TIMEOUT_MS = (typeof self.__MONMON_TIMEOUT_MS === 'number') ? self.__MONMON_TIMEOUT_MS : 2500;
const BG_ABORT_MS = (typeof self.__MONMON_BG_ABORT_MS === 'number') ? self.__MONMON_BG_ABORT_MS : 10000;
const PREWARM_CONCURRENCY = 2;

const JS_PRECACHE_NAMES = [
  'jquery-2.2.0.min',
  'common',
  'index',
  'disco',
  'global-search',
  'modal-scroll-lock',
  'waitingListModal',
  'nav-drawer',
  'spog-deck',
  'offline-mode',
  'nommon',
  'ed-says',
  'edOutgoings',
  'edNetWorth',
  'networth-chart',
  'spending-pace-chart',
  'historic-spent',
  'balances-chart',
  'erratic-balances',
  'savingsPlan',
  'monthly-pot',
  'transfers',
  'dig',
  'leaflet-heat',
  'bank-sync',
  'legmon'
];

const PRECACHE_URLS = [
  '/offline',
  '/assets/vendor/w3css/w3.css?v=' + MONMON_BUILD_HASH,
  '/assets/vendor/fontawesome/css/all.min.css?v=' + MONMON_BUILD_HASH,
  '/assets/vendor/fonts/fonts.css?v=' + MONMON_BUILD_HASH,
  '/assets/vendor/fonts/inter-latin.woff2',
  '/assets/vendor/fonts/inter-latin-ext.woff2',
  '/assets/vendor/fonts/plus-jakarta-sans-latin.woff2',
  '/assets/vendor/fonts/plus-jakarta-sans-latin-ext.woff2',
  '/assets/vendor/fontawesome/webfonts/fa-solid-900.woff2',
  '/assets/vendor/fontawesome/webfonts/fa-regular-400.woff2',
  '/assets/vendor/fontawesome/webfonts/fa-brands-400.woff2',
  '/assets/vendor/leaflet/leaflet.css?v=' + MONMON_BUILD_HASH,
  '/assets/vendor/leaflet/leaflet.js?v=' + MONMON_BUILD_HASH,
  '/assets/stylesheets/main.' + MONMON_BUILD_HASH + '.css'
]
  .concat(JS_PRECACHE_NAMES.map((name) => '/assets/javascripts/' + name + '.' + MONMON_BUILD_HASH + '.js'))
  .concat([
    '/assets/images/monmon-monster.svg',
    '/assets/images/favicon.svg',
    '/assets/images/favicon.png',
    '/assets/images/apple-touch-icon.png',
    '/assets/site.webmanifest'
  ]);

const REQUIRED_PRECACHE_URLS = new Set([
  '/offline',
  '/assets/vendor/w3css/w3.css?v=' + MONMON_BUILD_HASH,
  '/assets/stylesheets/main.' + MONMON_BUILD_HASH + '.css',
  '/assets/javascripts/common.' + MONMON_BUILD_HASH + '.js'
]);

const NON_CACHEABLE_HTML_PATHS = ['/login', '/signup', '/waitinglist', '/offline', '/callback'];
const AUTH_REDIRECT_PATHS = ['/login', '/signup', '/waitinglist', '/callback'];

const FAST_JSON_PATHS = new Set([
  '/api/spog/metrics',
  '/api/spog/spending-pace',
  '/api/transfers',
  '/api/networth',
  '/api/savings-plan',
  '/api/dig/data',
  '/api/monzo-dig/data',
  '/api/waste-bin'
]);

const AI_JSON_PATHS = new Set([
  '/api/nommon/plan',
  '/api/ed-says',
  '/api/ed-says-outgoings',
  '/api/ed-says-networth'
]);

const NETWORK_ONLY_EXACT_PATHS = new Set([
  '/sw.js',
  '/api/version',
  '/health',
  '/login',
  '/signup',
  '/callback',
  '/logout',
  '/centralLogout',
  '/api/search',
  '/api/monzo/webhook',
  '/api/user/accessStatus'
]);

let activePrewarmId = 0;
let activePrewarmController = null;

function toAbsoluteUrl(pathOrUrl) {
  return new URL(pathOrUrl, self.location.origin).href;
}

function matchesAnyPathPrefix(pathname, prefixes) {
  return prefixes.some((p) => pathname === p || pathname.startsWith(p + '/'));
}

function isNetworkOnly(url) {
  if (url.origin !== self.location.origin) {
    return true;
  }
  const p = url.pathname;
  if (NETWORK_ONLY_EXACT_PATHS.has(p)) {
    return true;
  }
  return (
    p.startsWith('/login/') ||
    p.startsWith('/signup/') ||
    p.startsWith('/callback/') ||
    p.startsWith('/timeTravel/') ||
    p.startsWith('/api/auth/') ||
    p.startsWith('/api/legmon/') ||
    p.startsWith('/bank/monzo/')
  );
}

function isStaticAsset(url) {
  const p = url.pathname;
  return (
    p.startsWith('/assets/') ||
    p.startsWith('/public/') ||
    p === '/favicon.ico' ||
    p === '/site.webmanifest' ||
    (p.startsWith('/apple-touch-icon') && p.endsWith('.png'))
  );
}

function isAiJsonEndpoint(url) {
  return AI_JSON_PATHS.has(url.pathname);
}

function isFastJsonEndpoint(url) {
  return FAST_JSON_PATHS.has(url.pathname);
}

function isTimeTravelResponse(response) {
  return Boolean(response && response.headers && response.headers.get('X-MonMon-Time-Travel') === '1');
}

function getFinalUrl(response, fallbackUrl) {
  const raw = (response && response.url) ? response.url : fallbackUrl;
  if (!raw) return null;
  try {
    return new URL(raw, self.location.origin);
  } catch (_e) {
    return null;
  }
}

function isCacheableHtmlResponse(response, requestUrl) {
  if (!response || response.type === 'opaqueredirect' || response.status !== 200) {
    return false;
  }
  if (isTimeTravelResponse(response)) {
    return false;
  }
  const ct = (response.headers.get('content-type') || '').toLowerCase();
  if (!ct.includes('text/html')) {
    return false;
  }
  const finalUrl = getFinalUrl(response, requestUrl);
  if (!finalUrl || finalUrl.origin !== self.location.origin) {
    return false;
  }
  if (matchesAnyPathPrefix(finalUrl.pathname, NON_CACHEABLE_HTML_PATHS)) {
    return false;
  }
  if (response.redirected && !response.url) {
    return false;
  }
  return true;
}

function isCacheableJsonResponse(response, requestUrl) {
  if (!response || response.status !== 200) {
    return false;
  }
  if (isTimeTravelResponse(response)) {
    return false;
  }
  const finalUrl = getFinalUrl(response, requestUrl);
  if (!finalUrl || finalUrl.origin !== self.location.origin || matchesAnyPathPrefix(finalUrl.pathname, NON_CACHEABLE_HTML_PATHS)) {
    return false;
  }
  const ct = (response.headers.get('content-type') || '').toLowerCase();
  return ct.includes('application/json');
}

function canonicalCacheKey(url) {
  if (url.pathname === '/api/nommon/plan' && url.searchParams.get('force') !== 'true') {
    return new URL('/api/nommon/plan', self.location.origin).href;
  }
  return url.href;
}

async function cloneResponseWithHeaders(response, extraHeaders) {
  const cloned = response.clone();
  const headers = new Headers(cloned.headers);
  headers.delete('content-encoding');
  headers.delete('content-length');
  for (const [key, value] of Object.entries(extraHeaders)) {
    headers.set(key, value);
  }
  const body = await cloned.arrayBuffer();
  return new Response(body, {
    status: cloned.status,
    statusText: cloned.statusText || '',
    headers: headers
  });
}

async function putWithTimestamp(cache, key, response, shouldWrite) {
  if (!cache) return null;
  const stamped = await cloneResponseWithHeaders(response, {
    'X-MonMon-Cached-At': new Date().toISOString()
  });
  if (shouldWrite && !shouldWrite()) {
    return null;
  }
  await cache.put(key, stamped);
  return stamped;
}

async function cloneWithFallbackHeader(cachedResponse, headerName) {
  const existingCachedAt = cachedResponse.headers.get('X-MonMon-Cached-At');
  const extraHeaders = { [headerName]: '1' };
  if (!existingCachedAt) {
    extraHeaders['X-MonMon-Cached-At'] = new Date().toISOString();
  }
  return cloneResponseWithHeaders(cachedResponse, extraHeaders);
}

async function cacheAiResponseIfValid(cache, cacheKey, url, response, shouldWrite) {
  if (!cache || !isCacheableJsonResponse(response, url.href)) {
    return false;
  }
  if (url.pathname === '/api/nommon/plan') {
    try {
      const payload = await response.clone().json();
      if (!payload || payload.plan === null || !payload.plan) {
        return false;
      }
    } catch (_e) {
      return false;
    }
  }
  try {
    const written = await putWithTimestamp(cache, cacheKey, response, shouldWrite);
    return Boolean(written);
  } catch (_e) {
    return false;
  }
}

async function matchPagesCache(pagesCache, url, overrideKey) {
  if (overrideKey) {
    const exact = await pagesCache.match(overrideKey);
    if (exact) return exact;
  }
  const hrefMatch = await pagesCache.match(url.href);
  if (hrefMatch) return hrefMatch;
  if (url.search) {
    return pagesCache.match(new URL(url.pathname, self.location.origin).href);
  }
  return undefined;
}

async function broadcastToClients(message, event) {
  if (event && event.ports && event.ports[0] && typeof event.ports[0].postMessage === 'function') {
    event.ports[0].postMessage(message);
  }
  let sentToSource = false;
  if (self.clients && typeof self.clients.matchAll === 'function') {
    const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of clientList) {
      if (client && typeof client.postMessage === 'function') {
        client.postMessage(message);
        if (event && event.source === client) {
          sentToSource = true;
        }
      }
    }
  }
  if (!sentToSource && event && event.source && typeof event.source.postMessage === 'function') {
    event.source.postMessage(message);
  }
}

function offlineJsonResponse() {
  return new Response(
    JSON.stringify({
      offline: true,
      error: 'MonMon is offline (read-only mode).'
    }),
    {
      status: 503,
      statusText: 'Service Unavailable',
      headers: { 'Content-Type': 'application/json; charset=utf-8' }
    }
  );
}

async function getCachedOfflineFallback() {
  try {
    const staticCache = await caches.open(STATIC_CACHE);
    return staticCache.match(toAbsoluteUrl('/offline'));
  } catch (_e) {
    return undefined;
  }
}

async function handleMutationRequest(request) {
  try {
    return await fetch(request);
  } catch (_err) {
    return offlineJsonResponse();
  }
}

async function handleStaticAsset(request, url) {
  const absKey = url.href;
  let staticCache = null;
  try {
    staticCache = await caches.open(STATIC_CACHE);
    const cached = (await staticCache.match(absKey)) || (await staticCache.match(request));
    if (cached) {
      return cached;
    }
  } catch (_e) {}

  const resp = await fetch(request);
  if (staticCache && resp && resp.status === 200) {
    try {
      await staticCache.put(absKey, resp.clone());
    } catch (_e) {}
  }
  return resp;
}

function buildTimeoutFetchRequest(request, signal) {
  if (!signal) return request;
  try {
    // Avoid Chromium TypeError when constructing Request from mode='navigate' with RequestInit;
    // preserve redirect: 'manual' on navigations so 3xx redirects pass through as opaqueredirect.
    return new Request(request.url, {
      method: request.method || 'GET',
      headers: request.headers,
      credentials: 'same-origin',
      mode: 'same-origin',
      redirect: request.mode === 'navigate' ? 'manual' : (request.redirect || 'follow'),
      signal: signal
    });
  } catch (_e) {
    return request;
  }
}

async function cacheNetworkFirstResponse(pagesCache, url, liveResp, isHtml, shouldWrite) {
  if (!pagesCache) return;
  try {
    if (isHtml) {
      if (isCacheableHtmlResponse(liveResp, url.href)) {
        await putWithTimestamp(pagesCache, url.href, liveResp, shouldWrite);
      }
    } else if (isCacheableJsonResponse(liveResp, url.href)) {
      await putWithTimestamp(pagesCache, url.href, liveResp, shouldWrite);
    }
  } catch (_e) {}
}

async function handleNetworkFirstWithTimeout(event, request, url, isHtml) {
  let pagesCache = null;
  try {
    pagesCache = await caches.open(PAGES_CACHE);
  } catch (_e) {}
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const fetchReq = buildTimeoutFetchRequest(request, controller ? controller.signal : undefined);

  let lieFiTimerId = null;
  let bgAbortTimerId = null;

  if (controller) {
    bgAbortTimerId = setTimeout(() => {
      try {
        controller.abort();
      } catch (_e) {}
    }, BG_ABORT_MS);
  }

  const networkPromise = fetch(fetchReq);
  networkPromise.catch(() => {});

  const timeoutPromise = new Promise((resolve) => {
    lieFiTimerId = setTimeout(() => {
      resolve({ timedOut: true });
    }, LIE_FI_TIMEOUT_MS);
  });

  const raceOutcome = await Promise.race([
    networkPromise.then(
      (response) => ({ timedOut: false, response }),
      (error) => ({ timedOut: false, error })
    ),
    timeoutPromise
  ]);

  if (lieFiTimerId !== null) {
    clearTimeout(lieFiTimerId);
    lieFiTimerId = null;
  }

  if (!raceOutcome.timedOut && !raceOutcome.error && raceOutcome.response) {
    if (bgAbortTimerId !== null) {
      clearTimeout(bgAbortTimerId);
      bgAbortTimerId = null;
    }
    const liveResp = raceOutcome.response;
    await cacheNetworkFirstResponse(pagesCache, url, liveResp, isHtml);
    return liveResp;
  }

  if (raceOutcome.timedOut) {
    const bgRefresh = networkPromise
      .then(async (liveResp) => {
        if (bgAbortTimerId !== null) {
          clearTimeout(bgAbortTimerId);
          bgAbortTimerId = null;
        }
        await cacheNetworkFirstResponse(pagesCache, url, liveResp, isHtml);
      })
      .catch(() => {
        if (bgAbortTimerId !== null) {
          clearTimeout(bgAbortTimerId);
          bgAbortTimerId = null;
        }
      });
    if (event && typeof event.waitUntil === 'function') {
      event.waitUntil(bgRefresh);
    }
  } else if (bgAbortTimerId !== null) {
    clearTimeout(bgAbortTimerId);
    bgAbortTimerId = null;
  }

  const cached = pagesCache ? await matchPagesCache(pagesCache, url) : undefined;
  if (cached) {
    const offlineResp = await cloneWithFallbackHeader(cached, 'X-MonMon-Offline');
    const cachedAt = offlineResp.headers.get('X-MonMon-Cached-At') || new Date().toISOString();
    await broadcastToClients({
      type: 'MONMON_OFFLINE_SERVED',
      url: url.pathname + url.search,
      cachedAt: cachedAt
    });
    return offlineResp;
  }

  if (isHtml) {
    const offlinePage = await getCachedOfflineFallback();
    if (offlinePage) {
      return offlinePage;
    }
    return new Response('<h1>Offline</h1><p>You are offline and this page is not cached.</p>', {
      status: 503,
      statusText: 'Service Unavailable',
      headers: { 'Content-Type': 'text/html; charset=utf-8' }
    });
  }

  return offlineJsonResponse();
}

async function handleAiJsonRequest(event, request, url) {
  if (url.searchParams.get('force') === 'true') {
    try {
      return await fetch(request);
    } catch (_err) {
      return offlineJsonResponse();
    }
  }

  let pagesCache = null;
  let cached = undefined;
  const cacheKey = canonicalCacheKey(url);
  try {
    pagesCache = await caches.open(PAGES_CACHE);
    cached = await pagesCache.match(cacheKey);
  } catch (_e) {}

  // Never attach timeout or abort controller to slow Mistral AI requests
  const networkPromise = fetch(request);

  if (!cached) {
    try {
      const liveResp = await networkPromise;
      await cacheAiResponseIfValid(pagesCache, cacheKey, url, liveResp);
      return liveResp;
    } catch (_err) {
      return offlineJsonResponse();
    }
  }

  networkPromise.catch(() => {});

  let lieFiTimerId = null;
  const timeoutPromise = new Promise((resolve) => {
    lieFiTimerId = setTimeout(() => {
      resolve({ timedOut: true });
    }, LIE_FI_TIMEOUT_MS);
  });

  const raceOutcome = await Promise.race([
    networkPromise.then(
      (response) => ({ timedOut: false, response }),
      (error) => ({ timedOut: false, error })
    ),
    timeoutPromise
  ]);

  if (lieFiTimerId !== null) {
    clearTimeout(lieFiTimerId);
    lieFiTimerId = null;
  }

  if (!raceOutcome.timedOut && !raceOutcome.error && raceOutcome.response) {
    await cacheAiResponseIfValid(pagesCache, cacheKey, url, raceOutcome.response);
    return raceOutcome.response;
  }

  if (raceOutcome.timedOut) {
    const bgRefresh = networkPromise
      .then(async (liveResp) => {
        await cacheAiResponseIfValid(pagesCache, cacheKey, url, liveResp);
      })
      .catch(() => {});
    if (event && typeof event.waitUntil === 'function') {
      event.waitUntil(bgRefresh);
    }
    return cloneWithFallbackHeader(cached, 'X-MonMon-Cached-Fallback');
  }

  // Network rejected outright while cached copy exists
  const offlineResp = await cloneWithFallbackHeader(cached, 'X-MonMon-Offline');
  const cachedAt = offlineResp.headers.get('X-MonMon-Cached-At') || new Date().toISOString();
  await broadcastToClients({
    type: 'MONMON_OFFLINE_SERVED',
    url: url.pathname + url.search,
    cachedAt: cachedAt
  });
  return offlineResp;
}

function isAuthRedirectOrUnauthorized(resp, reqUrl) {
  if (!resp) return true;
  if (resp.type === 'opaqueredirect' || resp.status === 401) {
    return true;
  }
  const finalUrl = getFinalUrl(resp, reqUrl.href);
  if (!finalUrl || finalUrl.origin !== self.location.origin) {
    return true;
  }
  if (matchesAnyPathPrefix(finalUrl.pathname, AUTH_REDIRECT_PATHS)) {
    return true;
  }
  return Boolean(resp.redirected && !resp.url);
}

function cancelActivePrewarm() {
  activePrewarmId++;
  if (activePrewarmController) {
    try {
      activePrewarmController.abort();
    } catch (_e) {}
    activePrewarmController = null;
  }
}

async function runPrewarm(urls) {
  cancelActivePrewarm();

  const runId = activePrewarmId;
  const list = Array.isArray(urls) ? urls.filter((u) => typeof u === 'string' && u.length > 0) : [];
  if (list.length === 0) {
    await broadcastToClients({
      type: 'MONMON_PREWARM_DONE',
      total: 0,
      warmedAt: new Date().toISOString()
    });
    return;
  }

  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  activePrewarmController = controller;

  const pagesCache = await caches.open(PAGES_CACHE);
  const total = list.length;
  let nextIdx = 0;
  let completed = 0;
  let aborted = false;

  const isRunActive = () => !aborted && runId === activePrewarmId;

  async function abortPrewarm(reason) {
    if (!isRunActive()) return;
    aborted = true;
    if (controller) {
      try {
        controller.abort();
      } catch (_e) {}
    }
    await broadcastToClients({
      type: 'MONMON_PREWARM_ABORTED',
      reason: reason
    });
  }

  async function worker() {
    while (isRunActive()) {
      const idx = nextIdx++;
      if (idx >= total) return;

      let reqUrl;
      try {
        reqUrl = new URL(list[idx], self.location.origin);
      } catch (_e) {
        continue;
      }

      let resp;
      try {
        resp = await fetch(reqUrl.href, {
          method: 'GET',
          credentials: 'same-origin',
          redirect: 'follow',
          headers: { 'X-MonMon-Prewarm': '1' },
          signal: controller ? controller.signal : undefined
        });
      } catch (_err) {
        await abortPrewarm('network_error');
        return;
      }

      if (!isRunActive()) return;

      if (isAuthRedirectOrUnauthorized(resp, reqUrl)) {
        await abortPrewarm('auth_redirect');
        return;
      }

      if (resp.status === 200 && !isTimeTravelResponse(resp)) {
        try {
          if (isAiJsonEndpoint(reqUrl)) {
            await cacheAiResponseIfValid(pagesCache, canonicalCacheKey(reqUrl), reqUrl, resp, isRunActive);
          } else if (isFastJsonEndpoint(reqUrl) || reqUrl.pathname.startsWith('/api/')) {
            if (isCacheableJsonResponse(resp, reqUrl.href)) {
              await putWithTimestamp(pagesCache, reqUrl.href, resp, isRunActive);
            }
          } else if (isCacheableHtmlResponse(resp, reqUrl.href)) {
            await putWithTimestamp(pagesCache, reqUrl.href, resp, isRunActive);
          }
        } catch (_e) {}
      }

      if (!isRunActive()) return;

      completed++;
      await broadcastToClients({
        type: 'MONMON_PREWARM_PROGRESS',
        completed: completed,
        total: total
      });
    }
  }

  const workerCount = Math.min(PREWARM_CONCURRENCY, total);
  const workers = [];
  for (let i = 0; i < workerCount; i++) {
    workers.push(worker());
  }
  await Promise.all(workers);

  if (activePrewarmController === controller) {
    activePrewarmController = null;
  }

  if (!aborted && runId === activePrewarmId) {
    await broadcastToClients({
      type: 'MONMON_PREWARM_DONE',
      total: total,
      warmedAt: new Date().toISOString()
    });
  }
}

async function deleteCachesByPrefix(prefix) {
  const keys = await caches.keys();
  await Promise.all(
    keys
      .filter((key) => key.startsWith(prefix))
      .map((key) => caches.delete(key))
  );
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const staticCache = await caches.open(STATIC_CACHE);
      const results = await Promise.allSettled(
        PRECACHE_URLS.map(async (assetPath) => {
          const absUrl = toAbsoluteUrl(assetPath);
          const resp = await fetch(absUrl, { credentials: 'same-origin' });
          if (!resp || resp.status !== 200) {
            throw new Error('Failed to precache ' + assetPath + ' (status ' + (resp ? resp.status : 'none') + ')');
          }
          await staticCache.put(absUrl, resp);
        })
      );

      for (let i = 0; i < PRECACHE_URLS.length; i++) {
        if (REQUIRED_PRECACHE_URLS.has(PRECACHE_URLS[i]) && results[i].status === 'rejected') {
          throw results[i].reason;
        }
      }

      await self.skipWaiting();
    })()
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(
        keys
          .filter((key) => key !== STATIC_CACHE && key !== PAGES_CACHE)
          .map((key) => caches.delete(key))
      );
      if (self.clients && typeof self.clients.claim === 'function') {
        await self.clients.claim();
      }
    })()
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (!request || !request.url) return;

  let url;
  try {
    url = new URL(request.url, self.location.origin);
  } catch (_e) {
    return;
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return;
  }

  if (url.origin !== self.location.origin) {
    return;
  }

  const method = (request.method || 'GET').toUpperCase();
  if (method !== 'GET') {
    if (method === 'HEAD' || method === 'OPTIONS') {
      return;
    }
    event.respondWith(handleMutationRequest(request));
    return;
  }

  if (isNetworkOnly(url)) {
    return;
  }

  if (isStaticAsset(url)) {
    event.respondWith(handleStaticAsset(request, url));
    return;
  }

  if (isAiJsonEndpoint(url)) {
    event.respondWith(handleAiJsonRequest(event, request, url));
    return;
  }

  if (isFastJsonEndpoint(url)) {
    event.respondWith(handleNetworkFirstWithTimeout(event, request, url, false));
    return;
  }

  const accept = (request.headers && request.headers.get('accept')) || '';
  if (request.mode === 'navigate' || accept.includes('text/html')) {
    event.respondWith(handleNetworkFirstWithTimeout(event, request, url, true));
  }
});

self.addEventListener('message', (event) => {
  const data = event && event.data;
  if (!data || typeof data.type !== 'string') return;

  if (data.type === 'MONMON_PREWARM') {
    const task = runPrewarm(data.urls);
    if (event.waitUntil) event.waitUntil(task);
    return;
  }

  if (data.type === 'MONMON_CLEAR_USER_CACHES') {
    cancelActivePrewarm();
    const task = (async () => {
      await deleteCachesByPrefix('monmon-pages-');
      await broadcastToClients({ type: 'MONMON_USER_CACHES_CLEARED' }, event);
    })();
    if (event.waitUntil) event.waitUntil(task);
    return;
  }

  if (data.type === 'MONMON_PURGE_CACHES') {
    cancelActivePrewarm();
    const task = (async () => {
      await deleteCachesByPrefix('monmon-');
      await broadcastToClients({ type: 'MONMON_CACHES_PURGED' }, event);
    })();
    if (event.waitUntil) event.waitUntil(task);
  }
});
