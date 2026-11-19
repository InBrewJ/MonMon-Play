// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('MonMon Offline Read-Only Mode, Lie-Fi & Version Updates E2E', () => {
  const BANNED_EXTERNAL_HOSTS = [
    'w3schools.com',
    'cdnjs.cloudflare.com',
    'fonts.googleapis.com',
    'fonts.gstatic.com',
    'unpkg.com',
  ];

  function getTargetOrigin(baseURL) {
    return new URL(baseURL || process.env.BASE_URL || 'http://localhost:9000').origin;
  }

  function buildAuthCookies(baseURL) {
    const origin = getTargetOrigin(baseURL);
    return [
      { name: 'user_id', value: 'demo', url: origin },
      { name: 'monmon_session', value: 'demo', url: origin },
      { name: 'authenticated', value: 'true', url: origin },
      { name: 'PLAY_SESSION', value: 'authenticated', url: origin },
    ];
  }

  async function loginIfRequired(page) {
    if (page.url().includes('auth') || page.url().includes('login')) {
      const isMonmonLogin = await page.locator('form[action="/login"]').isVisible({ timeout: 1000 }).catch(() => false);
      if (isMonmonLogin) {
        await page.fill('input[name="username"]', 'demo');
        await page.fill('input[name="password"]', 'demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('button[type="submit"]'),
        ]);
        return;
      }
      const isRauthy = await page.locator('input[type="email"]').isVisible({ timeout: 1000 }).catch(() => false);
      if (isRauthy) {
        await page.fill('input[type="email"]', 'demo@demo.com');
        await page.click('button:has-text("Login")');
        const pwd = page.locator('input[type="password"]');
        await pwd.waitFor({ state: 'visible' });
        await pwd.fill('demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('button:has-text("Login")'),
        ]);
      } else {
        await page.fill('#username', 'demo');
        await page.fill('#password', 'demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('#kc-login'),
        ]);
      }
    }
  }

  async function authenticateContext(context, baseURL) {
    await context.addCookies(buildAuthCookies(baseURL));
  }

  async function waitForServiceWorkerActive(page) {
    await page.waitForFunction(
      async () => {
        if (!('serviceWorker' in navigator)) return false;
        const reg = await navigator.serviceWorker.ready;
        return Boolean(reg && reg.active && navigator.serviceWorker.controller);
      },
      null,
      { timeout: 15000 }
    );
  }

  async function waitForPrewarmCompletion(page) {
    await page.waitForFunction(
      () => Boolean(localStorage.getItem('monmon_last_warm_at')),
      null,
      { timeout: 25000 }
    );
  }

  async function ensureNommonPlanCachedOnline(page) {
    const hasPlanCached = await page.evaluate(async () => {
      const keys = await caches.keys();
      const pageCacheKeys = keys.filter((k) => k.startsWith('monmon-pages-'));
      for (const k of pageCacheKeys) {
        const cache = await caches.open(k);
        const match = await cache.match(new URL('/api/nommon/plan', window.location.origin).href);
        if (match) return true;
      }
      return false;
    });

    if (!hasPlanCached) {
      await page.goto('/nommon', { waitUntil: 'load' });
      await loginIfRequired(page);
      await expect(page.locator('#nommon-content')).toBeVisible({ timeout: 15000 });
      await expect(page.locator('.nommon-item-check').first()).toBeVisible({ timeout: 15000 });
    }
  }

  test('1. Self-hosted assets check: zero external CDN requests on /spog, /nommon, and /dig', async ({ page, context, baseURL }) => {
    await authenticateContext(context, baseURL);

    const recordedUrls = [];
    context.on('request', (req) => {
      recordedUrls.push(req.url());
    });

    await page.goto('/spog', { waitUntil: 'load' });
    await loginIfRequired(page);
    await expect(page.locator('#spog-card-pot')).toBeVisible();
    await page.waitForLoadState('networkidle').catch(() => {});

    await page.goto('/nommon', { waitUntil: 'load' });
    await loginIfRequired(page);
    await expect(page.locator('#nommon-container')).toBeVisible();
    await page.waitForLoadState('networkidle').catch(() => {});

    await page.goto('/dig', { waitUntil: 'load' });
    await loginIfRequired(page);
    await expect(page.locator('#dig-stat-total')).toBeVisible();
    await page.waitForLoadState('networkidle').catch(() => {});

    const bannedHits = recordedUrls.filter((url) =>
      BANNED_EXTERNAL_HOSTS.some((host) => url.toLowerCase().includes(host))
    );
    expect(bannedHits).toEqual([]);

    expect(recordedUrls.some((u) => u.includes('/assets/vendor/w3css/w3.css'))).toBe(true);
    expect(recordedUrls.some((u) => u.includes('/assets/vendor/fontawesome/css/all.min.css'))).toBe(true);
    expect(recordedUrls.some((u) => u.includes('/assets/vendor/fonts/fonts.css'))).toBe(true);
    expect(recordedUrls.some((u) => u.includes('/assets/vendor/leaflet/leaflet.js'))).toBe(true);
  });

  test('2. SW registration, warming indicator, and Tube offline browsing on /nommon, /myOutgoings, /transfers', async ({ page, context, baseURL }) => {
    test.setTimeout(60000);
    await authenticateContext(context, baseURL);

    // Record every #offline-warm-indicator state transition from initial DOM ready
    await page.addInitScript(() => {
      window.__warmTransitions = [];
      function attachObserver() {
        const el = document.getElementById('offline-warm-indicator');
        if (!el || el.__monmonObserved) return;
        el.__monmonObserved = true;
        const record = () => {
          const state = el.getAttribute('data-state') || '';
          const text = (el.textContent || '').trim();
          const last = window.__warmTransitions[window.__warmTransitions.length - 1];
          if (!last || last.state !== state || last.text !== text) {
            window.__warmTransitions.push({ state, text });
          }
        };
        record();
        const obs = new MutationObserver(record);
        obs.observe(el, { attributes: true, childList: true, characterData: true, subtree: true });
      }
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', attachObserver);
      } else {
        attachObserver();
      }
    });

    await page.goto('/spog', { waitUntil: 'load' });
    await loginIfRequired(page);
    await expect(page.locator('#spog-card-pot')).toBeVisible();

    await waitForServiceWorkerActive(page);
    await waitForPrewarmCompletion(page);

    const lastWarmAt = await page.evaluate(() => localStorage.getItem('monmon_last_warm_at'));
    expect(lastWarmAt).toBeTruthy();
    expect(Number.isNaN(Date.parse(lastWarmAt))).toBe(false);

    const warmTransitions = await page.evaluate(() => window.__warmTransitions || []);
    const sawWarming = warmTransitions.some((t) => t.state === 'warming' && t.text.includes('🔥'));
    const sawDone = warmTransitions.some((t) => t.state === 'done' && t.text.includes('✓'));
    expect(sawWarming).toBe(true);
    expect(sawDone).toBe(true);

    // Ensure /api/nommon/plan is cached when demo has no locked plan in DB
    await ensureNommonPlanCachedOnline(page);

    try {
      await context.setOffline(true);

      // A. /nommon offline browsing + persistent shopping list checkboxes
      await page.goto('/nommon', { waitUntil: 'load' });

      const offlineBanner = page.locator('#monmon-offline-banner');
      await expect(offlineBanner).toBeVisible();
      await expect(offlineBanner.locator('svg.offline-banner-icon')).toBeVisible();
      await expect(offlineBanner.locator('.offline-banner-badge')).toHaveText('OFFLINE');
      await expect(page.locator('body')).toHaveClass(/is-offline/);

      await expect(page.locator('#nommon-content')).toBeVisible({ timeout: 10000 });
      await expect(page.locator('#btn-regenerate-nommon')).toBeDisabled();

      const firstNommonCheck = page.locator('.nommon-item-check').first();
      await expect(firstNommonCheck).toBeVisible();
      const itemName = await firstNommonCheck.getAttribute('data-name');
      expect(itemName).toBeTruthy();

      const wasChecked = await firstNommonCheck.isChecked();
      await firstNommonCheck.click();
      expect(await firstNommonCheck.isChecked()).toBe(!wasChecked);

      const savedNommonRaw = await page.evaluate(() => localStorage.getItem('nommon_checked_items'));
      expect(savedNommonRaw).toBeTruthy();
      const savedNommonList = JSON.parse(savedNommonRaw);
      if (!wasChecked) {
        expect(savedNommonList).toContain(itemName);
      } else {
        expect(savedNommonList).not.toContain(itemName);
      }

      await page.reload({ waitUntil: 'load' });
      await expect(page.locator('#monmon-offline-banner')).toBeVisible();
      await expect(page.locator('.nommon-item-check').first()).toBeVisible({ timeout: 10000 });
      expect(await page.locator('.nommon-item-check').first().isChecked()).toBe(!wasChecked);

      // B. /myOutgoings offline browsing + forms & DELETE blocking
      await page.goto('/myOutgoings', { waitUntil: 'load' });
      await expect(page.locator('#monmon-offline-banner')).toBeVisible();
      await expect(page.locator('#monmon-offline-banner svg.offline-banner-icon')).toBeVisible();
      await expect(page.locator('#monmon-offline-banner .offline-banner-badge')).toHaveText('OFFLINE');
      await expect(page.locator('body')).toHaveClass(/is-offline/);

      const mutatingRequests = [];
      let dialogTriggered = false;
      const trackMutatingReq = (req) => {
        const m = req.method().toUpperCase();
        if (m === 'POST' || m === 'PUT' || m === 'DELETE') {
          mutatingRequests.push({ method: m, url: req.url() });
        }
      };
      const trackDialog = async (dialog) => {
        dialogTriggered = true;
        await dialog.dismiss().catch(() => {});
      };
      context.on('request', trackMutatingReq);
      page.on('dialog', trackDialog);

      try {
        const deleteBtn = page.locator('button[data-method="DELETE"]:visible').first();
        await expect(deleteBtn).toBeVisible();
        await deleteBtn.click();

        const toast = page.locator('#monmon-offline-toast');
        await expect(toast).toBeVisible();
        await expect(toast).toContainText(/Offline/i);

        // Add Outgoing button and yearly target form submit must both be blocked offline
        await page.locator('button[onclick*="openOutgoingModal"]').click();
        await expect(page.locator('#outgoing-modal')).not.toBeVisible();
        await expect(toast).toBeVisible();

        await page.locator('.outgoings-tab-btn[data-tab="yearly"]').click();
        const yearlySubmit = page.locator('#yearly-bills-config-panel form button[type="submit"]');
        await expect(yearlySubmit).toBeVisible();
        await yearlySubmit.click();

        await expect(toast).toBeVisible();
        await expect(page.locator('#monmon-page-loader')).not.toHaveClass(/is-active/);
        expect(page.url()).toContain('/myOutgoings');
        expect(dialogTriggered).toBe(false);
        expect(mutatingRequests).toEqual([]);
      } finally {
        context.off('request', trackMutatingReq);
        page.off('dialog', trackDialog);
      }

      // C. /transfers offline browsing + persistent Transfer Window checkboxes
      await page.goto('/transfers', { waitUntil: 'load' });
      await expect(page.locator('#monmon-offline-banner')).toBeVisible();
      await expect(page.locator('#monmon-offline-banner svg.offline-banner-icon')).toBeVisible();
      await expect(page.locator('#monmon-offline-banner .offline-banner-badge')).toHaveText('OFFLINE');
      await expect(page.locator('h2:has-text("Payday Transfer Window")')).toBeVisible();

      const firstTransferChk = page.locator('#panel-next input[type="checkbox"][id^="chk-"]').first();
      await expect(firstTransferChk).toBeAttached();
      const chkId = await firstTransferChk.getAttribute('id');
      expect(chkId).toBeTruthy();

      const parts = chkId.split('-');
      const windowType = parts[1];
      const itemId = parts.slice(2).join('-');
      const expectedStorageKey = `monmon_payday_transfer_done_${windowType}_${itemId}`;

      const wasTransferChecked = await firstTransferChk.isChecked();
      const transferToggleBox = page.locator(`#panel-next label.transfer-chk-box:has(#${chkId})`);
      await expect(transferToggleBox).toBeVisible();
      await transferToggleBox.click();

      expect(await firstTransferChk.isChecked()).toBe(!wasTransferChecked);
      const savedTransferVal = await page.evaluate((k) => localStorage.getItem(k), expectedStorageKey);
      expect(savedTransferVal).toBe(!wasTransferChecked ? 'true' : null);

      await page.reload({ waitUntil: 'load' });
      await expect(page.locator('#monmon-offline-banner')).toBeVisible();
      expect(await page.locator(`#${chkId}`).isChecked()).toBe(!wasTransferChecked);
    } finally {
      await context.setOffline(false);
    }
  });

  test('3. Sainsbury\'s Lie-Fi 2.5s timeout fallback serves cached /nommon within 4.5s and shows offline banner', async ({ page, context, baseURL }) => {
    test.setTimeout(45000);
    await authenticateContext(context, baseURL);

    await page.goto('/spog', { waitUntil: 'load' });
    await loginIfRequired(page);
    await waitForServiceWorkerActive(page);
    await waitForPrewarmCompletion(page);
    await ensureNommonPlanCachedOnline(page);

    if (!page.url().includes('/spog')) {
      await page.goto('/spog', { waitUntil: 'load' });
    }

    const pendingTimers = [];
    const delay10s = () =>
      new Promise((resolve) => {
        const entry = { done: false, timer: null, resolve: null };
        entry.resolve = () => {
          if (!entry.done) {
            entry.done = true;
            resolve();
          }
        };
        entry.timer = setTimeout(entry.resolve, 10000);
        pendingTimers.push(entry);
      });

    // Use context.route so Service Worker fetch() calls are intercepted in Chromium
    await context.route((url) => url.pathname === '/nommon', async (route) => {
      await delay10s();
      await route.continue().catch(() => {});
    });
    await context.route((url) => url.pathname === '/api/version', async (route) => {
      await delay10s();
      await route.continue().catch(() => {});
    });
    await context.route((url) => url.pathname === '/api/nommon/plan', async (route) => {
      await delay10s();
      await route.continue().catch(() => {});
    });

    try {
      const startMs = Date.now();
      await page.goto('/nommon', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('#nommon-container')).toBeVisible({ timeout: 4500 });
      const elapsedMs = Date.now() - startMs;
      expect(elapsedMs).toBeLessThan(4500);

      const offlineBanner = page.locator('#monmon-offline-banner');
      await expect(offlineBanner).toBeVisible({ timeout: 6000 });
      await expect(offlineBanner.locator('svg.offline-banner-icon')).toBeVisible();
      await expect(offlineBanner.locator('.offline-banner-badge')).toHaveText('OFFLINE');
      await expect(page.locator('.nommon-item-check').first()).toBeVisible({ timeout: 6000 });
    } finally {
      for (const entry of pendingTimers) {
        clearTimeout(entry.timer);
        entry.resolve();
      }
      await context.unrouteAll({ behavior: 'ignoreErrors' });
    }
  });

  test('4. Version mismatch auto-update purges monmon-* caches and reloads once without loop', async ({ page, context, baseURL }) => {
    test.setTimeout(45000);
    await authenticateContext(context, baseURL);

    await page.goto('/spog', { waitUntil: 'load' });
    await loginIfRequired(page);
    await waitForServiceWorkerActive(page);
    await waitForPrewarmCompletion(page);

    // Verify caches exist and plant sentinels to prove full monmon-* purge before reload
    const cacheSetup = await page.evaluate(async () => {
      const keysBefore = await caches.keys();
      const pagesKey = keysBefore.find((k) => k.startsWith('monmon-pages-'));
      if (pagesKey) {
        const c = await caches.open(pagesKey);
        await c.put('/__purge_sentinel__', new Response('stale', { status: 200 }));
      }
      const sentinelCache = await caches.open('monmon-stale-sentinel');
      await sentinelCache.put('/__sentinel__', new Response('stale', { status: 200 }));
      return {
        hasStatic: keysBefore.some((k) => k.startsWith('monmon-static-')),
        hasPages: Boolean(pagesKey),
      };
    });
    expect(cacheSetup.hasStatic).toBe(true);
    expect(cacheSetup.hasPages).toBe(true);

    let spogNavCount = 0;
    let versionApiCount = 0;
    const warnMessages = [];

    page.on('console', (msg) => {
      if (msg.type() === 'warning') {
        warnMessages.push(msg.text());
      }
    });
    page.on('request', (req) => {
      if (req.isNavigationRequest()) {
        try {
          if (new URL(req.url()).pathname === '/spog') {
            spogNavCount++;
          }
        } catch (_e) {}
      }
    });

    await page.route('**/api/version', async (route) => {
      versionApiCount++;
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({
          buildHash: 'newer999',
          buildCommit: 'newer999',
          buildNumber: '999',
          buildTimestamp: '2026-11-19',
        }),
      });
    });

    const secondLoadGuardPromise = page.waitForEvent('console', {
      predicate: (msg) =>
        msg.type() === 'warning' &&
        msg.text().includes('Update reload already attempted for build newer999'),
      timeout: 15000,
    });

    await page.reload({ waitUntil: 'load' });
    await secondLoadGuardPromise;
    await page.waitForLoadState('load');

    // Wait briefly to confirm no infinite reload loop occurs
    await page.waitForTimeout(1500);

    expect(spogNavCount).toBe(2);
    expect(versionApiCount).toBe(2);
    expect(await page.evaluate(() => sessionStorage.getItem('monmon_updating_to_hash'))).toBe('newer999');
    expect(await page.evaluate(() => localStorage.getItem('monmon_last_warm_at'))).toBeNull();
    await expect(page.locator('#monmon-update-banner')).toBeHidden();
    expect(warnMessages.some((m) => m.includes('Update reload already attempted for build newer999'))).toBe(true);

    const purgeResult = await page.evaluate(async () => {
      const keysAfter = await caches.keys();
      const hasSentinelCache = keysAfter.includes('monmon-stale-sentinel');
      let hasSentinelEntry = false;
      let hasOldPrewarmedOutgoings = false;
      for (const k of keysAfter) {
        if (k.startsWith('monmon-pages-')) {
          const c = await caches.open(k);
          if (await c.match('/__purge_sentinel__')) hasSentinelEntry = true;
          if (await c.match(new URL('/myOutgoings', window.location.origin).href)) hasOldPrewarmedOutgoings = true;
        }
      }
      return { hasSentinelCache, hasSentinelEntry, hasOldPrewarmedOutgoings };
    });
    expect(purgeResult.hasSentinelCache).toBe(false);
    expect(purgeResult.hasSentinelEntry).toBe(false);
    expect(purgeResult.hasOldPrewarmedOutgoings).toBe(false);
  });

  test('5. Logout cache purge removes monmon-pages-* while preserving monmon-static-*', async ({ page, context, baseURL }) => {
    await authenticateContext(context, baseURL);

    await page.goto('/spog', { waitUntil: 'load' });
    await loginIfRequired(page);
    await waitForServiceWorkerActive(page);
    await waitForPrewarmCompletion(page);

    const keysBeforeLogout = await page.evaluate(() => caches.keys());
    expect(keysBeforeLogout.some((k) => k.startsWith('monmon-pages-'))).toBe(true);
    expect(keysBeforeLogout.some((k) => k.startsWith('monmon-static-'))).toBe(true);

    const origin = getTargetOrigin(baseURL);
    // Execute backend /centralLogout without following external OIDC provider redirect (so test works without Rauthy)
    await page.route(
      (url) => url.origin === origin && (url.pathname === '/centralLogout' || url.pathname === '/logout'),
      async (route) => {
        const resp = await route.fetch({ maxRedirects: 0 });
        expect([200, 302, 303, 307]).toContain(resp.status());
        await route.fulfill({
          status: 200,
          contentType: 'text/html; charset=utf-8',
          body: '<!doctype html><html><body><h1>Signed Out</h1></body></html>',
        });
      }
    );

    await page.locator('#nav-toggle').click();
    const logoutLink = page.locator('.drawer-logout a[href="/centralLogout"]');
    await expect(logoutLink).toBeVisible();
    await logoutLink.click();

    await page.waitForURL((url) => url.pathname === '/centralLogout' || url.pathname === '/login', { timeout: 15000 });

    const keysAfterLogout = await page.evaluate(() => caches.keys());
    expect(keysAfterLogout.filter((k) => k.startsWith('monmon-pages-'))).toEqual([]);
    expect(keysAfterLogout.some((k) => k.startsWith('monmon-static-'))).toBe(true);
    expect(await page.evaluate(() => localStorage.getItem('monmon_last_warm_at'))).toBeNull();
  });
});
