/**
 * MonMon Offline Read-Only Mode Client Controller
 * Plain JavaScript (Zero CoffeeScript).
 *
 * Responsibilities:
 * - Register root-scoped Service Worker (/sw.js)
 * - Verify frontend vs. backend BUILD_HASH parity via GET /api/version
 * - Manage persistent offline banner, snapshot freshness & Time Travel suppression
 * - Orchestrate background pre-warming & 🔥 -> ✓ warming indicator
 * - Enforce read-only UI guards offline & purge user caches on logout
 */
(function () {
    'use strict';

    var PREWARM_URLS = [
        // Priority 1 (Sainsbury's & Primary)
        '/spog',
        '/nommon',
        '/api/nommon/plan?lockedOnly=true',
        // Priority 2 (Core Financial Views)
        '/myOutgoings',
        '/myAccounts',
        '/myIncomings',
        '/myBalances',
        '/savingsPlan',
        '/myMonthlyPot',
        '/netWorth',
        '/transfers',
        // Priority 3 (Secondary & Analytics)
        '/spendingPace',
        '/historicSpent',
        '/bank',
        '/dig',
        '/wasteBin',
        '/settings'
    ];

    var VERSION_TIMEOUT_MS = 2500;
    var PREWARM_THROTTLE_MS = 15 * 60 * 1000;
    var PREWARM_IDLE_FALLBACK_MS = 2000;
    var WARM_DONE_HOLD_MS = 2200;
    var WARM_FADE_MS = 300;
    var UPDATE_RELOAD_CEILING_MS = 2000;
    var LOGOUT_CLEAR_CEILING_MS = 500;
    var SNAPSHOT_AGE_REFRESH_MS = 30000;
    var TOAST_DURATION_MS = 3000;
    var RETRY_FEEDBACK_MS = 2500;

    var DEFAULT_OFFLINE_TOAST_MSG = 'Offline — editing is disabled in read-only mode.';
    var LOGOUT_OFFLINE_TOAST_MSG = 'Offline — sign out is unavailable while disconnected.';
    var DEFAULT_OFFLINE_SUBTEXT = 'Read-Only Mode';
    var TIME_TRAVEL_OFFLINE_SUBTEXT = 'Read-Only Mode (Live snapshot — Time Travel paused offline)';

    var STORAGE_LAST_WARM_AT = 'monmon_last_warm_at';
    var STORAGE_LAST_BUILD_HASH = 'monmon_last_build_hash';
    var SESSION_UPDATING_HASH = 'monmon_updating_to_hash';
    var SESSION_RECONNECTED_PREWARM = 'monmon_reconnected_prewarm';

    var EDIT_LINK_PREFIXES = [
        '/editoutgoing/',
        '/editaccount/',
        '/editincoming/',
        '/editsavingsplanitem/',
        '/savingsplan/edititem/'
    ];

    var isOfflineFlag = false;
    var hasCompletedLiveLoad = false;
    var servedFromCacheOffline = false;
    var currentSnapshotTimestamp = null;
    var warmState = 'idle';

    var snapshotAgeTimer = null;
    var toastTimer = null;
    var warmHoldTimer = null;
    var warmFadeTimer = null;
    var retryFeedbackTimer = null;
    var prewarmIdleHandle = null;
    var prewarmPendingForce = false;
    var swRegistration = null;
    var swRegistrationPromise = null;
    var activeVersionCheckPromise = null;
    var listenersBound = false;

    function safeStorageGet(storage, key) {
        try {
            return storage ? storage.getItem(key) : null;
        } catch (_e) {
            return null;
        }
    }

    function safeStorageSet(storage, key, value) {
        try {
            if (storage) storage.setItem(key, String(value));
        } catch (_e) {}
    }

    function safeStorageRemove(storage, key) {
        try {
            if (storage) storage.removeItem(key);
        } catch (_e) {}
    }

    function getLocalStorage() {
        return typeof localStorage !== 'undefined' ? localStorage : null;
    }

    function getSessionStorage() {
        return typeof sessionStorage !== 'undefined' ? sessionStorage : null;
    }

    function parseTimestampMs(raw) {
        if (raw === null || raw === undefined || raw === '') return NaN;
        var s = String(raw).trim();
        if (!s) return NaN;
        if (/^\d+$/.test(s)) {
            return Number(s);
        }
        var parsed = Date.parse(s);
        return isNaN(parsed) ? NaN : parsed;
    }

    function hasTimeTravelCookie() {
        try {
            var rawCookie = (typeof document !== 'undefined' && document.cookie) ? document.cookie : '';
            if (!rawCookie) return false;
            var parts = rawCookie.split(';');
            for (var i = 0; i < parts.length; i++) {
                var c = parts[i].trim();
                if (c.indexOf('monmon_time_travel=') === 0) {
                    var val = c.substring(19).trim();
                    if (val && val !== 'false' && val !== '0') {
                        return true;
                    }
                }
            }
        } catch (_e) {}
        return false;
    }

    function getPageBuildHash() {
        try {
            if (typeof document === 'undefined' || typeof document.querySelector !== 'function') return '';
            var meta = document.querySelector('meta[name="monmon-build-hash"]');
            return meta ? String(meta.getAttribute('content') || '').trim() : '';
        } catch (_e) {
            return '';
        }
    }

    function closestEl(el, selector) {
        if (!el) return null;
        if (typeof el.closest === 'function') {
            return el.closest(selector);
        }
        var cur = el;
        while (cur) {
            if (typeof cur.matches === 'function' && cur.matches(selector)) {
                return cur;
            }
            cur = cur.parentElement || cur.parentNode || null;
        }
        return null;
    }

    function hasOfflineAllowedAttr(el) {
        if (!el || typeof el.hasAttribute !== 'function') return false;
        if (!el.hasAttribute('data-offline-allowed')) return false;
        return el.getAttribute('data-offline-allowed') !== 'false';
    }

    function resolveUrl(rawHref) {
        try {
            var base = (typeof window !== 'undefined' && window.location && window.location.href)
                ? window.location.href
                : 'http://localhost';
            return new URL(rawHref, base);
        } catch (_e) {
            return null;
        }
    }

    function getAnchorRawHref(anchor) {
        if (!anchor) return '';
        if (typeof anchor.getAttribute === 'function') {
            var attr = anchor.getAttribute('href');
            if (attr !== null && attr !== undefined) return String(attr);
        }
        return anchor.href ? String(anchor.href) : '';
    }

    function getPathnameFromHref(rawHref) {
        if (!rawHref) return '';
        var parsed = resolveUrl(rawHref);
        if (parsed && parsed.pathname) return parsed.pathname;
        var qIdx = rawHref.indexOf('?');
        var hIdx = rawHref.indexOf('#');
        var end = rawHref.length;
        if (qIdx !== -1 && qIdx < end) end = qIdx;
        if (hIdx !== -1 && hIdx < end) end = hIdx;
        return rawHref.slice(0, end);
    }

    function isSameOriginAnchor(rawHref) {
        if (!rawHref) return false;
        var parsed = resolveUrl(rawHref);
        if (!parsed) return true;
        if (typeof window !== 'undefined' && window.location && window.location.origin) {
            return parsed.origin === window.location.origin;
        }
        return true;
    }

    function isLogoutHref(rawHref) {
        if (!rawHref || !isSameOriginAnchor(rawHref)) return false;
        var p = getPathnameFromHref(rawHref).toLowerCase();
        return p === '/logout' || p === '/centrallogout';
    }

    function isEditOnlyHref(rawHref) {
        if (!rawHref || !isSameOriginAnchor(rawHref)) return false;
        var p = getPathnameFromHref(rawHref).toLowerCase();
        for (var i = 0; i < EDIT_LINK_PREFIXES.length; i++) {
            if (p.indexOf(EDIT_LINK_PREFIXES[i]) === 0) {
                return true;
            }
        }
        return false;
    }

    function isTimeTravelHref(rawHref) {
        if (!rawHref || !isSameOriginAnchor(rawHref)) return false;
        var p = getPathnameFromHref(rawHref).toLowerCase();
        return p.indexOf('/timetravel/') === 0;
    }

    function monmonIsOffline() {
        var bodyOffline = Boolean(
            typeof document !== 'undefined' &&
            document.body &&
            document.body.classList &&
            document.body.classList.contains('is-offline')
        );
        return Boolean(isOfflineFlag || bodyOffline);
    }

    function dispatchOfflineChange(offline) {
        try {
            if (typeof window === 'undefined' || typeof window.dispatchEvent !== 'function') return;
            var ev;
            if (typeof CustomEvent === 'function') {
                ev = new CustomEvent('monmon:offline-change', { detail: { offline: Boolean(offline) } });
            } else {
                ev = { type: 'monmon:offline-change', detail: { offline: Boolean(offline) } };
            }
            window.dispatchEvent(ev);
        } catch (_e) {}
    }

    function formatSnapshotAge(tsRaw) {
        var tsMs = parseTimestampMs(tsRaw);
        if (isNaN(tsMs)) {
            return 'Cached snapshot';
        }
        var diffMs = Math.max(0, Date.now() - tsMs);
        var diffSec = Math.floor(diffMs / 1000);
        if (diffSec < 60) {
            return 'Last synced just now';
        }
        var diffMin = Math.floor(diffSec / 60);
        if (diffMin < 60) {
            return 'Last synced ' + diffMin + 'm ago';
        }
        var diffHours = Math.floor(diffMin / 60);
        if (diffHours < 24) {
            return 'Last synced ' + diffHours + 'h ago';
        }
        var diffDays = Math.floor(diffHours / 24);
        return 'Last synced ' + diffDays + 'd ago';
    }

    function updateSnapshotAgeDisplay() {
        var ageEl = typeof document !== 'undefined' ? document.getElementById('offline-snapshot-age') : null;
        if (!ageEl) return;
        var ts = currentSnapshotTimestamp || safeStorageGet(getLocalStorage(), STORAGE_LAST_WARM_AT);
        ageEl.textContent = formatSnapshotAge(ts);
    }

    function startSnapshotAgeTicker() {
        updateSnapshotAgeDisplay();
        if (snapshotAgeTimer !== null) return;
        snapshotAgeTimer = setInterval(updateSnapshotAgeDisplay, SNAPSHOT_AGE_REFRESH_MS);
    }

    function stopSnapshotAgeTicker() {
        if (snapshotAgeTimer !== null) {
            clearInterval(snapshotAgeTimer);
            snapshotAgeTimer = null;
        }
    }

    function updateTickerSafely() {
        try {
            if (typeof window !== 'undefined' && typeof window.monmonRenderLiveTicker === 'function') {
                window.monmonRenderLiveTicker();
            }
        } catch (_e) {}
    }

    function enterOffline(cachedAtIso) {
        var wasOffline = monmonIsOffline();
        if (cachedAtIso && !isNaN(parseTimestampMs(cachedAtIso))) {
            currentSnapshotTimestamp = String(cachedAtIso);
        } else if (!currentSnapshotTimestamp) {
            var storedWarm = safeStorageGet(getLocalStorage(), STORAGE_LAST_WARM_AT);
            if (storedWarm && !isNaN(parseTimestampMs(storedWarm))) {
                currentSnapshotTimestamp = storedWarm;
            }
        }

        isOfflineFlag = true;
        if (typeof document !== 'undefined' && document.body && document.body.classList) {
            document.body.classList.add('is-offline');
        }

        var banner = typeof document !== 'undefined' ? document.getElementById('monmon-offline-banner') : null;
        if (banner) {
            banner.style.display = 'block';
        }

        var subtext = typeof document !== 'undefined' ? document.getElementById('offline-banner-subtext') : null;
        if (subtext) {
            subtext.textContent = hasTimeTravelCookie()
                ? TIME_TRAVEL_OFFLINE_SUBTEXT
                : DEFAULT_OFFLINE_SUBTEXT;
        }

        var ttBanner = typeof document !== 'undefined' ? document.getElementById('monmon-time-travel-banner') : null;
        if (ttBanner) {
            ttBanner.style.display = 'none';
        }

        startSnapshotAgeTicker();
        if (warmState === 'warming') {
            setWarmState('idle');
        }
        updateTickerSafely();

        if (!wasOffline) {
            dispatchOfflineChange(true);
        }
    }

    function exitOffline() {
        var wasOffline = monmonIsOffline();
        isOfflineFlag = false;
        if (typeof document !== 'undefined' && document.body && document.body.classList) {
            document.body.classList.remove('is-offline');
        }

        var banner = typeof document !== 'undefined' ? document.getElementById('monmon-offline-banner') : null;
        if (banner) {
            banner.style.display = 'none';
        }

        var ttBanner = typeof document !== 'undefined' ? document.getElementById('monmon-time-travel-banner') : null;
        if (ttBanner) {
            ttBanner.style.display = hasTimeTravelCookie() ? 'block' : 'none';
        }

        stopSnapshotAgeTicker();
        updateTickerSafely();

        if (wasOffline) {
            dispatchOfflineChange(false);
        }
    }

    function monmonShowOfflineToast(customMsg) {
        var toast = typeof document !== 'undefined' ? document.getElementById('monmon-offline-toast') : null;
        if (!toast) return;
        toast.textContent = customMsg || DEFAULT_OFFLINE_TOAST_MSG;
        toast.style.display = 'block';
        if (toastTimer !== null) {
            clearTimeout(toastTimer);
        }
        toastTimer = setTimeout(function () {
            toast.style.display = 'none';
            toastTimer = null;
        }, TOAST_DURATION_MS);
    }

    function clearWarmTimers() {
        if (warmHoldTimer !== null) {
            clearTimeout(warmHoldTimer);
            warmHoldTimer = null;
        }
        if (warmFadeTimer !== null) {
            clearTimeout(warmFadeTimer);
            warmFadeTimer = null;
        }
    }

    function setWarmState(state, detailText) {
        warmState = state || 'idle';
        clearWarmTimers();

        var el = typeof document !== 'undefined' ? document.getElementById('offline-warm-indicator') : null;
        if (!el) return;

        if (warmState === 'warming') {
            el.setAttribute('data-state', 'warming');
            if (el.dataset) el.dataset.state = 'warming';
            el.textContent = '🔥';
            var warmingTitle = detailText || 'Warming offline pages…';
            el.setAttribute('title', warmingTitle);
            el.setAttribute('aria-label', warmingTitle);
            if (el.classList) el.classList.remove('is-fading');
            el.style.display = 'inline-flex';
            return;
        }

        if (warmState === 'done') {
            el.setAttribute('data-state', 'done');
            if (el.dataset) el.dataset.state = 'done';
            el.textContent = '✓';
            el.setAttribute('title', 'Offline pages ready');
            el.setAttribute('aria-label', 'Offline pages ready');
            if (el.classList) el.classList.remove('is-fading');
            el.style.display = 'inline-flex';

            warmHoldTimer = setTimeout(function () {
                warmHoldTimer = null;
                if (el.classList) el.classList.add('is-fading');
                warmFadeTimer = setTimeout(function () {
                    warmFadeTimer = null;
                    if (warmState === 'done') {
                        setWarmState('idle');
                    }
                }, WARM_FADE_MS);
            }, WARM_DONE_HOLD_MS);
            return;
        }

        warmState = 'idle';
        el.setAttribute('data-state', 'idle');
        if (el.dataset) el.dataset.state = 'idle';
        el.textContent = '';
        if (el.classList) el.classList.remove('is-fading');
        if (typeof el.removeAttribute === 'function') {
            el.removeAttribute('title');
            el.removeAttribute('aria-label');
        }
        el.style.display = 'none';
    }

    function getSyncActiveSW() {
        if (typeof navigator === 'undefined' || !navigator.serviceWorker) return null;
        if (navigator.serviceWorker.controller) return navigator.serviceWorker.controller;
        if (swRegistration && swRegistration.active) return swRegistration.active;
        return null;
    }

    function registerServiceWorker() {
        if (typeof navigator === 'undefined' || !navigator.serviceWorker || typeof navigator.serviceWorker.register !== 'function') {
            return Promise.resolve(null);
        }
        if (swRegistrationPromise) return swRegistrationPromise;
        swRegistrationPromise = Promise.resolve()
            .then(function () {
                return navigator.serviceWorker.register('/sw.js', { scope: '/' });
            })
            .then(function (reg) {
                swRegistration = reg || null;
                return swRegistration;
            })
            .catch(function () {
                return null;
            });
        return swRegistrationPromise;
    }

    function waitForSwMessage(expectedType, ceilingMs) {
        return new Promise(function (resolve) {
            if (typeof navigator === 'undefined' || !navigator.serviceWorker || typeof navigator.serviceWorker.addEventListener !== 'function') {
                resolve(false);
                return;
            }
            var settled = false;
            var timerId = null;

            function finish(matched) {
                if (settled) return;
                settled = true;
                if (timerId !== null) {
                    clearTimeout(timerId);
                    timerId = null;
                }
                if (typeof navigator.serviceWorker.removeEventListener === 'function') {
                    navigator.serviceWorker.removeEventListener('message', onMsg);
                }
                resolve(matched);
            }

            function onMsg(event) {
                var data = event && event.data;
                if (data && data.type === expectedType) {
                    finish(true);
                }
            }

            navigator.serviceWorker.addEventListener('message', onMsg);
            timerId = setTimeout(function () {
                finish(false);
            }, ceilingMs);
        });
    }

    function purgeWindowCaches(prefix) {
        if (typeof caches === 'undefined' || !caches || typeof caches.keys !== 'function') {
            return Promise.resolve();
        }
        return caches.keys()
            .then(function (keys) {
                var targets = keys.filter(function (k) {
                    return k.indexOf(prefix) === 0;
                });
                return Promise.all(targets.map(function (k) {
                    return caches.delete(k);
                }));
            })
            .catch(function () {});
    }

    function postSwAndAwaitAck(msgType, ackType, ceilingMs) {
        var swTarget = getSyncActiveSW();
        if (!swTarget || typeof swTarget.postMessage !== 'function') {
            return Promise.resolve();
        }
        var ackPromise = waitForSwMessage(ackType, ceilingMs);
        try {
            swTarget.postMessage({ type: msgType });
            return ackPromise;
        } catch (_e) {
            return Promise.resolve();
        }
    }

    function raceWithCeiling(tasks, ceilingMs) {
        return Promise.race([
            Promise.all(tasks),
            new Promise(function (resolve) {
                setTimeout(resolve, ceilingMs);
            })
        ]);
    }

    function purgeAllCachesAndUpdateSw() {
        var tasks = [
            purgeWindowCaches('monmon-'),
            postSwAndAwaitAck('MONMON_PURGE_CACHES', 'MONMON_CACHES_PURGED', UPDATE_RELOAD_CEILING_MS)
        ];

        if (swRegistration && typeof swRegistration.update === 'function') {
            tasks.push(
                Promise.resolve()
                    .then(function () {
                        return swRegistration.update();
                    })
                    .catch(function () {})
            );
        } else if (typeof navigator !== 'undefined' && navigator.serviceWorker && typeof navigator.serviceWorker.getRegistration === 'function') {
            tasks.push(
                navigator.serviceWorker.getRegistration()
                    .then(function (reg) {
                        if (reg && typeof reg.update === 'function') {
                            return reg.update();
                        }
                    })
                    .catch(function () {})
            );
        }

        return raceWithCeiling(tasks, UPDATE_RELOAD_CEILING_MS);
    }

    function clearUserCachesWithTimeout(ceilingMs) {
        var tasks = [
            purgeWindowCaches('monmon-pages-'),
            postSwAndAwaitAck('MONMON_CLEAR_USER_CACHES', 'MONMON_USER_CACHES_CLEARED', ceilingMs)
        ];
        return raceWithCeiling(tasks, ceilingMs);
    }

    function reloadPage() {
        if (typeof window !== 'undefined' && window.location && typeof window.location.reload === 'function') {
            window.location.reload();
        }
    }

    function navigateTo(url) {
        if (typeof window === 'undefined' || !window.location) return;
        var resolved = resolveUrl(url);
        var dest = resolved ? resolved.href : String(url);
        if (typeof window.location.assign === 'function') {
            window.location.assign(dest);
        } else {
            window.location.href = dest;
        }
    }

    function isAuthenticatedPage() {
        if (typeof document === 'undefined' || typeof document.getElementById !== 'function') return false;
        if (!document.getElementById('main-nav')) return false;
        if (typeof window !== 'undefined' && window.location && window.location.pathname) {
            var p = window.location.pathname.toLowerCase();
            if (
                p.indexOf('/login') === 0 ||
                p.indexOf('/signup') === 0 ||
                p.indexOf('/waitinglist') === 0 ||
                p.indexOf('/offline') === 0
            ) {
                return false;
            }
        }
        return true;
    }

    function isThrottledPrewarm() {
        var raw = safeStorageGet(getLocalStorage(), STORAGE_LAST_WARM_AT);
        var tsMs = parseTimestampMs(raw);
        if (isNaN(tsMs)) return false;
        return (Date.now() - tsMs) < PREWARM_THROTTLE_MS;
    }

    function canRunPrewarm(force) {
        if (!isAuthenticatedPage()) return false;
        if (monmonIsOffline()) return false;
        if (typeof navigator !== 'undefined' && navigator.onLine === false) return false;
        if (hasTimeTravelCookie()) return false;
        if (typeof document !== 'undefined' && document.body && document.body.classList && document.body.classList.contains('in-time-travel')) {
            return false;
        }
        if (!force && isThrottledPrewarm()) return false;
        if (!force && warmState === 'warming') return false;
        return true;
    }

    function sendPrewarmToSw(swTarget) {
        if (!swTarget || typeof swTarget.postMessage !== 'function') return false;
        try {
            swTarget.postMessage({
                type: 'MONMON_PREWARM',
                urls: PREWARM_URLS.slice()
            });
            setWarmState('warming');
            return true;
        } catch (_e) {
            return false;
        }
    }

    function triggerPrewarm(force) {
        var forced = Boolean(force);
        if (!canRunPrewarm(forced)) {
            return false;
        }

        var swTarget = getSyncActiveSW();
        if (swTarget) {
            return sendPrewarmToSw(swTarget);
        }

        if (typeof navigator !== 'undefined' && navigator.serviceWorker && navigator.serviceWorker.ready) {
            navigator.serviceWorker.ready
                .then(function (reg) {
                    if (reg && !swRegistration) swRegistration = reg;
                    if (!canRunPrewarm(forced)) return;
                    var readyTarget = getSyncActiveSW() || (reg && reg.active);
                    if (readyTarget) {
                        sendPrewarmToSw(readyTarget);
                    }
                })
                .catch(function () {});
        }
        return false;
    }

    function schedulePrewarm(force) {
        if (force) prewarmPendingForce = true;
        if (!isAuthenticatedPage() || hasTimeTravelCookie()) return;
        if (prewarmIdleHandle !== null) return;

        function runWhenIdle() {
            var shouldForce = prewarmPendingForce;
            prewarmPendingForce = false;

            if (typeof requestIdleCallback === 'function') {
                prewarmIdleHandle = requestIdleCallback(function () {
                    prewarmIdleHandle = null;
                    triggerPrewarm(shouldForce);
                }, { timeout: PREWARM_IDLE_FALLBACK_MS });
            } else {
                prewarmIdleHandle = setTimeout(function () {
                    prewarmIdleHandle = null;
                    triggerPrewarm(shouldForce);
                }, PREWARM_IDLE_FALLBACK_MS);
            }
        }

        if (typeof document !== 'undefined' && document.readyState === 'complete') {
            runWhenIdle();
        } else if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
            var onLoad = function () {
                if (typeof window.removeEventListener === 'function') {
                    window.removeEventListener('load', onLoad);
                }
                runWhenIdle();
            };
            window.addEventListener('load', onLoad);
        } else {
            runWhenIdle();
        }
    }

    function fetchVersionWithTimeout() {
        var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
        var timerId = null;

        if (controller) {
            timerId = setTimeout(function () {
                try {
                    controller.abort();
                } catch (_e) {}
            }, VERSION_TIMEOUT_MS);
        }

        var fetchOpts = {
            cache: 'no-store',
            credentials: 'same-origin'
        };
        if (controller) {
            fetchOpts.signal = controller.signal;
        }

        var doFetch = typeof fetch === 'function' ? fetch : (typeof window !== 'undefined' ? window.fetch : null);
        if (typeof doFetch !== 'function') {
            if (timerId !== null) clearTimeout(timerId);
            return Promise.reject(new Error('fetch unavailable'));
        }

        return doFetch('/api/version', fetchOpts)
            .then(function (res) {
                if (timerId !== null) {
                    clearTimeout(timerId);
                    timerId = null;
                }
                if (!res || !res.ok) {
                    throw new Error('Version probe failed with status ' + (res ? res.status : 'unknown'));
                }
                return res.json();
            })
            .catch(function (err) {
                if (timerId !== null) {
                    clearTimeout(timerId);
                    timerId = null;
                }
                throw err;
            });
    }

    function enterOfflineFromProbe() {
        if (!hasCompletedLiveLoad) {
            servedFromCacheOffline = true;
        }
        enterOffline();
    }

    function checkVersionAndSync(options) {
        if (activeVersionCheckPromise) {
            return activeVersionCheckPromise;
        }

        var opts = options || {};
        var pageBuildHash = getPageBuildHash();

        var runPromise = (function () {
            if (!opts.fromRetry && typeof navigator !== 'undefined' && navigator.onLine === false) {
                enterOfflineFromProbe();
                return Promise.resolve({ status: 'offline' });
            }

            return fetchVersionWithTimeout()
                .then(function (data) {
                    if (!data || !data.buildHash) {
                        enterOfflineFromProbe();
                        return { status: 'offline' };
                    }

                    var serverHash = String(data.buildHash).trim();
                    if (!pageBuildHash || serverHash === pageBuildHash) {
                        var sess = getSessionStorage();
                        var loc = getLocalStorage();
                        var updatingTo = safeStorageGet(sess, SESSION_UPDATING_HASH);
                        var prevBuildHash = safeStorageGet(loc, STORAGE_LAST_BUILD_HASH);
                        var reconnectedFlag = safeStorageGet(sess, SESSION_RECONNECTED_PREWARM) === '1';
                        var versionChanged = Boolean(
                            (updatingTo && updatingTo === pageBuildHash) ||
                            (prevBuildHash && pageBuildHash && prevBuildHash !== pageBuildHash)
                        );

                        safeStorageRemove(sess, SESSION_UPDATING_HASH);
                        safeStorageRemove(sess, SESSION_RECONNECTED_PREWARM);
                        if (pageBuildHash) {
                            safeStorageSet(loc, STORAGE_LAST_BUILD_HASH, pageBuildHash);
                        }

                        var wasOffline = monmonIsOffline();
                        if (wasOffline) {
                            if (opts.fromRetry || servedFromCacheOffline) {
                                safeStorageSet(sess, SESSION_RECONNECTED_PREWARM, '1');
                                reloadPage();
                                return { status: 'reloading', buildHash: serverHash };
                            }
                            hasCompletedLiveLoad = true;
                            exitOffline();
                            schedulePrewarm(true);
                        } else {
                            hasCompletedLiveLoad = true;
                            schedulePrewarm(versionChanged || reconnectedFlag);
                        }

                        return { status: 'ok', buildHash: serverHash };
                    }

                    // Version mismatch detected: guard against infinite reload loops
                    var session = getSessionStorage();
                    var attemptedHash = safeStorageGet(session, SESSION_UPDATING_HASH);
                    var updateBanner = typeof document !== 'undefined' ? document.getElementById('monmon-update-banner') : null;

                    if (attemptedHash === serverHash) {
                        if (typeof console !== 'undefined' && console.warn) {
                            console.warn('[MonMon] Update reload already attempted for build ' + serverHash + '; skipping reload to prevent loop.');
                        }
                        if (updateBanner) {
                            updateBanner.style.display = 'none';
                        }
                        hasCompletedLiveLoad = true;
                        if (monmonIsOffline()) {
                            exitOffline();
                        }
                        return { status: 'mismatch-loop-guarded', buildHash: serverHash };
                    }

                    safeStorageSet(session, SESSION_UPDATING_HASH, serverHash);
                    safeStorageRemove(getLocalStorage(), STORAGE_LAST_WARM_AT);
                    if (updateBanner) {
                        updateBanner.style.display = 'block';
                    }

                    return purgeAllCachesAndUpdateSw().then(function () {
                        reloadPage();
                        return { status: 'reloading', buildHash: serverHash };
                    });
                })
                .catch(function () {
                    enterOfflineFromProbe();
                    return { status: 'offline' };
                });
        })();

        activeVersionCheckPromise = runPromise;
        return runPromise.then(
            function (result) {
                activeVersionCheckPromise = null;
                return result;
            },
            function (err) {
                activeVersionCheckPromise = null;
                throw err;
            }
        );
    }

    function isLocallyAllowedOfflineControl(target) {
        if (!target) return false;
        if (closestEl(target, '[data-offline-allowed]')) {
            var allowedEl = closestEl(target, '[data-offline-allowed]');
            if (hasOfflineAllowedAttr(allowedEl)) return true;
        }
        if (closestEl(target, '#btn-offline-retry')) return true;
        if (closestEl(target, '.nommon-item-check, #btn-uncheck-all, .nommon-tab-btn')) return true;
        if (closestEl(target, 'input[id^="chk-"], .transfer-chk-box, .btn-clear-checks, #next-clear-btn, #last-clear-btn, .btn-copy-amt, .breakdown-toggle-btn, #tab-btn-next, #tab-btn-last, .tab-btn')) {
            return true;
        }
        return false;
    }

    function isOfflineBlockedAnchor(anchor) {
        if (!anchor || !monmonIsOffline()) return false;
        if (hasOfflineAllowedAttr(anchor)) return false;

        var rawHref = getAnchorRawHref(anchor);
        if (isLogoutHref(rawHref) || isEditOnlyHref(rawHref) || isTimeTravelHref(rawHref)) {
            return true;
        }
        if (anchor.id === 'nav-drawer-time-travel-link' || anchor.id === 'btn-stop-time-travel') {
            return true;
        }
        if (closestEl(anchor, '.drawer-logout')) {
            return true;
        }
        return false;
    }

    function shouldBlockOfflineClick(target) {
        if (!target) return null;
        if (isLocallyAllowedOfflineControl(target)) return null;

        var dataMethodEl = closestEl(target, '[data-method]');
        if (dataMethodEl && !hasOfflineAllowedAttr(dataMethodEl)) {
            var m = String(dataMethodEl.getAttribute('data-method') || '').toUpperCase();
            if (m && m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS') {
                return DEFAULT_OFFLINE_TOAST_MSG;
            }
        }

        var anchor = closestEl(target, 'a');
        if (anchor && isOfflineBlockedAnchor(anchor)) {
            var rawHref = getAnchorRawHref(anchor);
            return isLogoutHref(rawHref) ? LOGOUT_OFFLINE_TOAST_MSG : DEFAULT_OFFLINE_TOAST_MSG;
        }

        var submitBtn = closestEl(target, 'button[type="submit"], input[type="submit"]');
        if (!submitBtn) {
            var anyBtn = closestEl(target, 'button');
            if (anyBtn) {
                var btnType = (typeof anyBtn.getAttribute === 'function' ? anyBtn.getAttribute('type') : anyBtn.type) || '';
                if (!btnType && closestEl(anyBtn, 'form')) {
                    submitBtn = anyBtn;
                }
            }
        }
        if (submitBtn && !hasOfflineAllowedAttr(submitBtn)) {
            var parentForm = closestEl(submitBtn, 'form');
            if (!parentForm || !hasOfflineAllowedAttr(parentForm)) {
                return DEFAULT_OFFLINE_TOAST_MSG;
            }
        }

        var mutatingControl = closestEl(
            target,
            '.btn-delete, #btn-regenerate-nommon, #nommon-lock-wrapper button, #btn-save-postcode, #nav-drawer-time-travel-link, #btn-verify-monzo-tbl, #btn-refresh-webhooks, #btn-preview-align-payday, #btn-final-confirm-overwrite, #btn-save-preset-direct, #btn-delete-preset-direct, #btn-save-allocations, #btn-snap-monthly-pot, [onclick*="openOutgoingModal"], [onclick*="openIncomingModal"], [onclick*="openAccountModal"], [onclick*="openPauseModal"], [onclick*="openEditModal"], .btn-edit-preset'
        );
        if (mutatingControl && !hasOfflineAllowedAttr(mutatingControl)) {
            return DEFAULT_OFFLINE_TOAST_MSG;
        }

        return null;
    }

    function handleOnlineLogoutClick(e, anchor) {
        var rawHref = getAnchorRawHref(anchor);
        if (!rawHref) return;

        e.preventDefault();
        if (typeof e.stopPropagation === 'function') {
            e.stopPropagation();
        }

        safeStorageRemove(getLocalStorage(), STORAGE_LAST_WARM_AT);
        if (typeof window !== 'undefined' && typeof window.showMonmonLoader === 'function') {
            window.showMonmonLoader('Signing out...', 'Clearing offline cache');
        }

        clearUserCachesWithTimeout(LOGOUT_CLEAR_CEILING_MS).then(
            function () {
                navigateTo(rawHref);
            },
            function () {
                navigateTo(rawHref);
            }
        );
    }

    function showRetryFeedback(message) {
        var feedback = typeof document !== 'undefined' ? document.getElementById('offline-retry-feedback') : null;
        if (feedback) {
            feedback.textContent = message;
            feedback.style.display = 'inline';
            if (retryFeedbackTimer !== null) clearTimeout(retryFeedbackTimer);
            retryFeedbackTimer = setTimeout(function () {
                feedback.style.display = 'none';
                feedback.textContent = '';
                retryFeedbackTimer = null;
            }, RETRY_FEEDBACK_MS);
            return;
        }
        var btn = typeof document !== 'undefined' ? document.getElementById('btn-offline-retry') : null;
        if (btn) {
            btn.textContent = message;
            if (retryFeedbackTimer !== null) clearTimeout(retryFeedbackTimer);
            retryFeedbackTimer = setTimeout(function () {
                btn.textContent = 'Retry';
                retryFeedbackTimer = null;
            }, RETRY_FEEDBACK_MS);
        }
    }

    function handleRetryClick() {
        var btn = typeof document !== 'undefined' ? document.getElementById('btn-offline-retry') : null;
        if (btn) {
            btn.disabled = true;
            btn.textContent = 'Checking…';
        }
        checkVersionAndSync({ fromRetry: true }).then(function (result) {
            if (btn) {
                btn.disabled = false;
                btn.textContent = 'Retry';
            }
            if (!result || result.status === 'offline') {
                showRetryFeedback('Still offline');
            }
        });
    }

    function bindEventListeners() {
        if (listenersBound) return;
        listenersBound = true;

        if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
            document.addEventListener('submit', function (e) {
                if (!monmonIsOffline()) return;
                var form = e.target;
                if (hasOfflineAllowedAttr(form)) return;
                e.preventDefault();
                if (typeof e.stopPropagation === 'function') e.stopPropagation();
                monmonShowOfflineToast();
            }, true);

            document.addEventListener('click', function (e) {
                var target = e.target;
                if (!target) return;

                if (monmonIsOffline()) {
                    var blockMsg = shouldBlockOfflineClick(target);
                    if (blockMsg) {
                        e.preventDefault();
                        if (typeof e.stopPropagation === 'function') e.stopPropagation();
                        monmonShowOfflineToast(blockMsg);
                    }
                    return;
                }

                // Online logout interception: purge user HTML/API caches before navigating
                if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || (e.button !== undefined && e.button !== 0)) {
                    return;
                }
                var anchor = closestEl(target, 'a');
                if (!anchor) return;
                if (anchor.target && anchor.target !== '_self') return;
                var rawHref = getAnchorRawHref(anchor);
                if (isLogoutHref(rawHref)) {
                    handleOnlineLogoutClick(e, anchor);
                }
            }, true);
        }

        if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
            window.addEventListener('offline', function () {
                enterOffline();
            });
            window.addEventListener('online', function () {
                checkVersionAndSync({ fromOnlineEvent: true });
            });
        }

        if (typeof navigator !== 'undefined' && navigator.serviceWorker && typeof navigator.serviceWorker.addEventListener === 'function') {
            navigator.serviceWorker.addEventListener('message', function (event) {
                var data = event && event.data;
                if (!data || typeof data.type !== 'string') return;

                if (data.type === 'MONMON_OFFLINE_SERVED') {
                    servedFromCacheOffline = true;
                    enterOffline(data.cachedAt);
                    return;
                }

                if (data.type === 'MONMON_PREWARM_PROGRESS') {
                    var detail = (typeof data.completed === 'number' && typeof data.total === 'number')
                        ? 'Warming offline pages… (' + data.completed + '/' + data.total + ')'
                        : undefined;
                    setWarmState('warming', detail);
                    return;
                }

                if (data.type === 'MONMON_PREWARM_DONE') {
                    var warmedVal = data.warmedAt ? String(data.warmedAt) : new Date().toISOString();
                    safeStorageSet(getLocalStorage(), STORAGE_LAST_WARM_AT, warmedVal);
                    currentSnapshotTimestamp = warmedVal;
                    setWarmState('done');
                    return;
                }

                if (data.type === 'MONMON_PREWARM_ABORTED') {
                    setWarmState('idle');
                }
            });
        }
    }

    function initDomControls() {
        var retryBtn = typeof document !== 'undefined' ? document.getElementById('btn-offline-retry') : null;
        if (retryBtn && !retryBtn._monmonBoundRetry && typeof retryBtn.addEventListener === 'function') {
            retryBtn._monmonBoundRetry = true;
            retryBtn.addEventListener('click', handleRetryClick);
        }

        if (typeof navigator !== 'undefined' && navigator.onLine === false) {
            enterOfflineFromProbe();
        } else {
            checkVersionAndSync();
        }
    }

    function bootstrap() {
        registerServiceWorker();
        bindEventListeners();

        if (typeof document !== 'undefined' && document.readyState === 'loading' && typeof document.addEventListener === 'function') {
            document.addEventListener('DOMContentLoaded', initDomControls);
        } else {
            initDomControls();
        }
    }

    if (typeof window !== 'undefined') {
        window.monmonIsOffline = monmonIsOffline;
        window.monmonShowOfflineToast = monmonShowOfflineToast;
        window.monmonIsOfflineBlockedAnchor = isOfflineBlockedAnchor;
        window.monmonOfflineMode = {
            PREWARM_URLS: PREWARM_URLS,
            enterOffline: enterOffline,
            exitOffline: exitOffline,
            checkVersionAndSync: checkVersionAndSync,
            triggerPrewarm: triggerPrewarm,
            setWarmState: setWarmState,
            formatSnapshotAge: formatSnapshotAge
        };
    }

    bootstrap();
})();
