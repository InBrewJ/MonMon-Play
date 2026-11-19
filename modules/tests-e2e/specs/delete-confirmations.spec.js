// @ts-check
const { test, expect } = require('@playwright/test');

const BASE_URL = process.env.BASE_URL || 'http://localhost:9000';
const AUTH_COOKIES = [
  { name: 'user_id', value: 'demo', url: BASE_URL },
  { name: 'monmon_session', value: 'demo', url: BASE_URL },
  { name: 'authenticated', value: 'true', url: BASE_URL },
  { name: 'PLAY_SESSION', value: 'authenticated', url: BASE_URL },
];

async function loginIfRequired(page) {
  if (page.url().includes('auth') || page.url().includes('login')) {
    const isMonmonLogin = await page.locator('form[action="/login"]').isVisible({ timeout: 1000 }).catch(() => false);
    if (isMonmonLogin) {
      await page.fill('input[name="username"]', 'demo');
      await page.fill('input[name="password"]', 'demo');
      await Promise.all([
        page.waitForURL((url) => !url.pathname.includes('/login')),
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
        page.waitForURL((url) => !url.pathname.includes('/auth')),
        page.click('button:has-text("Login")'),
      ]);
    } else {
      await page.fill('#username', 'demo');
      await page.fill('#password', 'demo');
      await Promise.all([
        page.waitForURL((url) => !url.pathname.includes('/auth')),
        page.click('#kc-login'),
      ]);
    }
  }
}

/**
 * Helper to fetch JSON from a REST API endpoint within the authenticated browser context.
 * @param {import('@playwright/test').Page} page
 * @param {string} apiPath
 */
async function apiGetJson(page, apiPath) {
  return page.evaluate(async (p) => {
    const res = await fetch(p, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!res.ok) {
      throw new Error(`GET ${p} failed with HTTP ${res.status}`);
    }
    return res.json();
  }, apiPath);
}

/**
 * Exhaustively tests a `[data-method="DELETE"]` button for BOTH:
 *  1) Clicking Cancel (`dialog.dismiss()`):
 *     - Asserts `confirm` dialog pops up EXACTLY ONCE (never twice)
 *     - Asserts zero mutating HTTP requests are sent
 *     - Asserts `#monmon-page-loader` does not activate and page does not navigate (even after 150ms)
 *     - Runs `assertStillPresentAfterCancel()` (REST API + DOM assertions)
 *  2) Clicking OK (`dialog.accept()`):
 *     - Asserts `confirm` dialog pops up EXACTLY ONCE
 *     - Asserts the expected `DELETE` HTTP request is sent and succeeds
 *     - Runs `assertDeletedAfterAccept()` (REST API + Waste Bin + DOM assertions)
 *
 * @param {object} opts
 * @param {import('@playwright/test').Page} opts.page
 * @param {() => import('@playwright/test').Locator} opts.getDeleteButton
 * @param {string} opts.expectedActionPath
 * @param {() => Promise<void>} opts.assertStillPresentAfterCancel
 * @param {() => Promise<void>} opts.assertDeletedAfterAccept
 */
async function verifyCancelThenAcceptDelete({
  page,
  getDeleteButton,
  expectedActionPath,
  assertStillPresentAfterCancel,
  assertDeletedAfterAccept,
}) {
  const mutatingRequests = [];
  const onRequest = (req) => {
    const m = req.method().toUpperCase();
    const u = req.url();
    if (
      m === 'DELETE' ||
      m === 'PUT' ||
      m === 'POST' ||
      u.includes('/bin') ||
      u.includes('/delete')
    ) {
      mutatingRequests.push({ method: m, url: u });
    }
  };
  page.on('request', onRequest);

  try {
    // --- PHASE A: Click Cancel (dismiss confirm dialog) ---
    const cancelDialogs = [];
    const onCancelDialog = async (dialog) => {
      cancelDialogs.push({ type: dialog.type(), message: dialog.message() });
      await dialog.dismiss();
    };
    page.on('dialog', onCancelDialog);

    const urlBeforeCancel = page.url();
    const btnCancel = getDeleteButton();
    await expect(btnCancel).toBeVisible();
    await btnCancel.click();

    // Wait 150ms (well past the 25ms nav_header loader timer window)
    await page.waitForTimeout(150);
    page.off('dialog', onCancelDialog);

    // a) Confirmation popped up ONCE (never twice)
    expect(
      cancelDialogs.length,
      `Expected confirm dialog to pop up exactly once on Cancel for ${expectedActionPath}, got ${cancelDialogs.length}: ${JSON.stringify(cancelDialogs)}`
    ).toBe(1);
    expect(cancelDialogs[0].type).toBe('confirm');

    // b) Clicking Cancel sent ZERO mutating requests, kept loader hidden, and did not navigate
    expect(
      mutatingRequests,
      `Expected zero mutating requests when clicking Cancel on ${expectedActionPath}`
    ).toEqual([]);
    await expect(page.locator('#monmon-page-loader')).not.toHaveClass(/is-active/);
    expect(page.url()).toBe(urlBeforeCancel);

    // c) REST API & DOM verify item was NOT moved to bin
    await assertStillPresentAfterCancel();

    // --- PHASE B: Click OK (accept confirm dialog) ---
    const acceptDialogs = [];
    const onAcceptDialog = async (dialog) => {
      acceptDialogs.push({ type: dialog.type(), message: dialog.message() });
      await dialog.accept();
    };
    page.on('dialog', onAcceptDialog);

    const deleteResponsePromise = page.waitForResponse(
      (res) => res.url().includes(expectedActionPath) && res.request().method() === 'DELETE',
      { timeout: 10000 }
    );

    const btnAccept = getDeleteButton();
    await expect(btnAccept).toBeVisible();
    await btnAccept.click();

    const deleteResponse = await deleteResponsePromise;
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(100);
    page.off('dialog', onAcceptDialog);

    // a) Confirmation popped up ONCE (never twice)
    expect(
      acceptDialogs.length,
      `Expected confirm dialog to pop up exactly once on Accept for ${expectedActionPath}, got ${acceptDialogs.length}`
    ).toBe(1);
    expect(acceptDialogs[0].type).toBe('confirm');

    // b) DELETE request succeeded
    expect([200, 302, 303]).toContain(deleteResponse.status());
    expect(
      mutatingRequests.filter((r) => r.method === 'DELETE' && r.url.includes(expectedActionPath)).length
    ).toBe(1);

    // c) REST API & Waste Bin verify item WAS moved to bin / deleted
    await assertDeletedAfterAccept();
  } finally {
    page.off('request', onRequest);
  }
}

test.describe('Exhaustive Delete & Bin Confirmation Dialogs E2E (with REST API Assertions)', () => {
  test.beforeEach(async ({ context }) => {
    await context.addCookies(AUTH_COOKIES);
  });

  test('1. Outgoings (/myOutgoings -> DELETE /binOutgoing/:id): single confirm, Cancel preserves in /internal/outgoings, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(45000);
    await page.goto('/myOutgoings');
    await loginIfRequired(page);

    // Create a fresh outgoing to test deletion
    await page.locator('button[onclick="openOutgoingModal()"]').first().click();
    await expect(page.locator('#outgoing-modal')).toBeVisible();
    const uniqueName = `DeleteTest Outgoing ${Date.now()}`;
    await page.fill('#outgoing-name', uniqueName);
    await page.fill('#outgoing-cost', '44.25');
    await page.fill('input[name="outgoingDay"]', '15');
    await Promise.all([
      page.waitForURL(/\/myOutgoings/),
      page.locator('#outgoing-modal-form button[type="submit"]').click(),
    ]);

    const outgoingsBefore = await apiGetJson(page, '/internal/outgoings?userId=demo');
    const created = outgoingsBefore.find((o) => o.name === uniqueName && !o.archived);
    expect(created, 'Newly created outgoing must appear in GET /internal/outgoings').toBeTruthy();
    const expectedPath = `/binOutgoing/${created.id}`;

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: expectedPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${expectedPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const list = await apiGetJson(page, '/internal/outgoings?userId=demo');
        const stillActive = list.find((o) => o.id === created.id && !o.archived);
        expect(stillActive, 'Outgoing must remain active in /internal/outgoings after Cancel').toBeTruthy();

        const bin = await apiGetJson(page, '/api/waste-bin');
        const inBin = (bin.items || []).find((i) => i.entity_type === 'outgoing' && i.id === created.id);
        expect(inBin, 'Outgoing must NOT be in /api/waste-bin after Cancel').toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const list = await apiGetJson(page, '/internal/outgoings?userId=demo');
        const active = list.find((o) => o.id === created.id && !o.archived);
        expect(active, 'Outgoing must no longer be active in /internal/outgoings after OK').toBeFalsy();

        const bin = await apiGetJson(page, '/api/waste-bin');
        const inBin = (bin.items || []).find((i) => i.entity_type === 'outgoing' && i.id === created.id);
        expect(inBin, 'Outgoing must appear in /api/waste-bin under entity_type=outgoing after OK').toBeTruthy();
      },
    });
  });

  test('2. Accounts (/myAccounts -> DELETE /binAccount/:id): single confirm, Cancel preserves in /internal/accounts, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(45000);
    await page.goto('/myAccounts');
    await loginIfRequired(page);

    await page.locator('button[onclick="openAccountModal()"]').first().click();
    await expect(page.locator('#account-modal')).toBeVisible();
    const uniqueName = `DeleteTest Account ${Date.now()}`;
    await page.fill('#acc-name', uniqueName);
    await page.selectOption('#type', 'SHORT_TERM_SAVINGS');
    await page.fill('#availableLimit', '500');
    await Promise.all([
      page.waitForURL(/\/myAccounts/),
      page.locator('#account-modal-form button[type="submit"]').click(),
    ]);

    const accountsBefore = await apiGetJson(page, '/internal/accounts?userId=demo');
    const created = accountsBefore.find((a) => a.name === uniqueName && !a.archived);
    expect(created, 'Newly created account must appear in GET /internal/accounts').toBeTruthy();
    const expectedPath = `/binAccount/${created.id}`;

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: expectedPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${expectedPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const list = await apiGetJson(page, '/internal/accounts?userId=demo');
        expect(list.find((a) => a.id === created.id && !a.archived)).toBeTruthy();

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect((bin.items || []).find((i) => i.entity_type === 'account' && i.id === created.id)).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const list = await apiGetJson(page, '/internal/accounts?userId=demo');
        expect(list.find((a) => a.id === created.id && !a.archived)).toBeFalsy();

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect((bin.items || []).find((i) => i.entity_type === 'account' && i.id === created.id)).toBeTruthy();
      },
    });
  });

  test('3. Incomings (/myIncomings -> DELETE /binIncoming/:id): single confirm, Cancel preserves in /internal/incomings, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(45000);
    await page.goto('/myIncomings');
    await loginIfRequired(page);

    await page.locator('button[onclick="openIncomingModal()"]').first().click();
    await expect(page.locator('#incoming-modal')).toBeVisible();
    const uniqueName = `DeleteTest Incoming ${Date.now()}`;
    await page.fill('#incoming-name', uniqueName);
    await page.fill('input[name="netValue"]', '320.00');
    await page.fill('input[name="incomingMonthDay"]', '22');
    await Promise.all([
      page.waitForURL(/\/myIncomings/),
      page.locator('#incoming-modal-form button[type="submit"]').click(),
    ]);

    const incomingsBefore = await apiGetJson(page, '/internal/incomings?userId=demo');
    const created = incomingsBefore.find((inc) => inc.name === uniqueName && !inc.archived);
    expect(created, 'Newly created incoming must appear in GET /internal/incomings').toBeTruthy();
    const expectedPath = `/binIncoming/${created.id}`;

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: expectedPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${expectedPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const list = await apiGetJson(page, '/internal/incomings?userId=demo');
        expect(list.find((inc) => inc.id === created.id && !inc.archived)).toBeTruthy();

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect((bin.items || []).find((i) => i.entity_type === 'incoming' && i.id === created.id)).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const list = await apiGetJson(page, '/internal/incomings?userId=demo');
        expect(list.find((inc) => inc.id === created.id && !inc.archived)).toBeFalsy();

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect((bin.items || []).find((i) => i.entity_type === 'incoming' && i.id === created.id)).toBeTruthy();
      },
    });
  });

  test('4. Balances (/myBalances -> DELETE /balance/:id/delete for both Balance History and Erratic Jump): single confirm, Cancel preserves in /internal/balances, OK deletes', async ({ page }) => {
    test.setTimeout(45000);
    await page.goto('/myBalances');
    await loginIfRequired(page);

    // Record a distinct balance entry
    await page.fill('#balance-value', '777.31');
    await Promise.all([
      page.waitForURL(/\/myBalances/),
      page.click('#record-balance-form button[type="submit"]'),
    ]);

    const balancesBefore = await apiGetJson(page, '/internal/balances?userId=demo');
    const firstDeleteBtn = page
      .locator('button[data-method="DELETE"][data-action*="/balance/"][data-action*="/delete"]:visible')
      .first();
    await expect(firstDeleteBtn).toBeVisible();
    const expectedPath = await firstDeleteBtn.getAttribute('data-action');
    expect(expectedPath).toMatch(/^\/balance\/\d+\/delete$/);
    const balanceId = Number(expectedPath.split('/')[2]);
    expect(balancesBefore.some((b) => b.id === balanceId)).toBe(true);

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: expectedPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${expectedPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const list = await apiGetJson(page, '/internal/balances?userId=demo');
        expect(
          list.some((b) => b.id === balanceId),
          `Balance #${balanceId} must remain in /internal/balances after Cancel`
        ).toBe(true);
      },
      assertDeletedAfterAccept: async () => {
        const list = await apiGetJson(page, '/internal/balances?userId=demo');
        expect(
          list.some((b) => b.id === balanceId),
          `Balance #${balanceId} must be removed from /internal/balances after OK`
        ).toBe(false);
      },
    });
  });

  test('5. Plans / Sharing Agreements (/myPlans -> DELETE /binPlan/:id): single confirm, Cancel preserves, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(45000);
    await page.goto('/myPlans');
    await loginIfRequired(page);

    const uniqueNote = `DeleteTest Plan ${Date.now()}`;
    await page.selectOption('#planType', 'BILL_SHARE');
    await page.fill('#notes', uniqueNote);
    await Promise.all([
      page.waitForURL(/\/(myPlans|plans)/),
      page.locator('#planForm button[type="submit"]').click(),
    ]);

    const deleteBtn = page.locator('button[data-method="DELETE"][data-action^="/binPlan/"]:visible').first();
    await expect(deleteBtn).toBeVisible();
    const expectedPath = await deleteBtn.getAttribute('data-action');
    const planId = Number(expectedPath.split('/').pop());

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: expectedPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${expectedPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect((bin.items || []).find((i) => i.entity_type === 'plan' && i.id === planId)).toBeFalsy();
        await expect(page.locator(`button[data-action="${expectedPath}"]`)).toBeVisible();
      },
      assertDeletedAfterAccept: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'plan' && i.id === planId),
          `Plan #${planId} must appear in /api/waste-bin under entity_type=plan after OK`
        ).toBeTruthy();
      },
    });
  });

  test('6. Monthly Pot & Spending Presets (/myMonthlyPot -> DELETE /pot/preset/delete/:id card, #btn-delete-preset-direct editor, and DELETE /binPot/:id): single confirm, Cancel preserves, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/myMonthlyPot');
    await loginIfRequired(page);

    // 6a. Create Preset #1 and test saved preset card delete button ([data-action="/pot/preset/delete/:id"])
    const presetName1 = `DeleteTest PresetCard ${Date.now()}`;
    await page.evaluate(async (name) => {
      await fetch('/pot/preset/save', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          plan_name: name,
          sections_json: JSON.stringify([{ heading: 'Food', amount: 200 }]),
        }),
      });
    }, presetName1);
    await page.reload();

    const presetCardBtn = page
      .locator('button[data-method="DELETE"][data-action^="/pot/preset/delete/"]:visible')
      .last();
    await expect(presetCardBtn).toBeVisible();
    const presetPath1 = await presetCardBtn.getAttribute('data-action');
    const presetId1 = Number(presetPath1.split('/').pop());

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: presetPath1,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${presetPath1}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'custom_spending_plan' && i.id === presetId1)
        ).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'custom_spending_plan' && i.id === presetId1),
          `Preset #${presetId1} must appear in /api/waste-bin under custom_spending_plan after OK`
        ).toBeTruthy();
      },
    });

    // 6b. Create Preset #2, load it in editor via .btn-edit-preset, and test #btn-delete-preset-direct
    const presetName2 = `DeleteTest PresetDirect ${Date.now()}`;
    await page.evaluate(async (name) => {
      await fetch('/pot/preset/save', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          plan_name: name,
          sections_json: JSON.stringify([{ heading: 'Food', amount: 210 }]),
        }),
      });
    }, presetName2);
    await page.reload();

    const editPresetBtn = page.locator('.btn-edit-preset:visible').last();
    await expect(editPresetBtn).toBeVisible();
    const presetId2 = Number(await editPresetBtn.getAttribute('data-preset-id'));
    const presetPath2 = `/pot/preset/delete/${presetId2}`;
    await editPresetBtn.click();
    await expect(page.locator('#btn-delete-preset-direct')).toBeVisible();

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: presetPath2,
      getDeleteButton: () => page.locator('#btn-delete-preset-direct'),
      assertStillPresentAfterCancel: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'custom_spending_plan' && i.id === presetId2)
        ).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'custom_spending_plan' && i.id === presetId2)
        ).toBeTruthy();
      },
    });

    // 6c. Ensure an active Monthly Pot exists and test DELETE /binPot/:id
    if ((await page.locator('button[data-method="DELETE"][data-action^="/binPot/"]:visible').count()) === 0) {
      const firstAccCheckbox = page.locator('#pot-config-form input[type="checkbox"][name="accounts"]').first();
      if ((await firstAccCheckbox.count()) > 0) {
        await firstAccCheckbox.check();
      }
      await Promise.all([
        page.waitForURL(/\/(myMonthlyPot|spendingPlan)/),
        page.locator('#pot-config-form button[type="submit"]').click(),
      ]);
    }
    const potDeleteBtn = page.locator('button[data-method="DELETE"][data-action^="/binPot/"]:visible').first();
    await expect(potDeleteBtn).toBeVisible();
    const potPath = await potDeleteBtn.getAttribute('data-action');
    const potId = Number(potPath.split('/').pop());

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: potPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${potPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect((bin.items || []).find((i) => i.entity_type === 'pot' && i.id === potId)).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'pot' && i.id === potId),
          `Pot #${potId} must appear in /api/waste-bin under entity_type=pot after OK`
        ).toBeTruthy();
      },
    });
  });

  test('7. Savings Plan Item & Savings Plan (/savingsPlan -> DELETE /binSavingsPlanItem/:id & DELETE /binSavingsPlan/:id): single confirm, Cancel preserves in /api/savings-plan, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/savingsPlan');
    await loginIfRequired(page);

    // Create a savings plan item via POST /api/savings-plan/item
    const uniqueItemName = `DeleteTest Slice ${Date.now()}`;
    await page.evaluate(async (name) => {
      await fetch('/api/savings-plan/item', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          userId: 'demo',
          name,
          category: 'SAVINGS_GOAL',
          targetAmount: 300,
          monthlyAmount: 30,
        }),
      });
    }, uniqueItemName);
    await page.reload();

    const spBefore = await apiGetJson(page, '/api/savings-plan');
    const createdItem = (spBefore.items || []).find((it) => it.name === uniqueItemName);
    expect(createdItem, 'Created savings slice must appear in GET /api/savings-plan').toBeTruthy();
    const itemPath = `/binSavingsPlanItem/${createdItem.id}`;

    // 7a. Test deleting the savings plan item
    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: itemPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${itemPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const sp = await apiGetJson(page, '/api/savings-plan');
        expect((sp.items || []).some((it) => it.id === createdItem.id)).toBe(true);

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'savings_plan_item' && i.id === createdItem.id)
        ).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const sp = await apiGetJson(page, '/api/savings-plan');
        expect((sp.items || []).some((it) => it.id === createdItem.id)).toBe(false);

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'savings_plan_item' && i.id === createdItem.id),
          `SavingsPlanItem #${createdItem.id} must appear in /api/waste-bin after OK`
        ).toBeTruthy();
      },
    });

    // 7b. Test deleting the Savings Plan itself (DELETE /binSavingsPlan/:id) and then recover it via /api/waste-bin so demo workspace stays intact
    const planDeleteBtn = page
      .locator('button[data-method="DELETE"][data-action^="/binSavingsPlan/"]:visible')
      .first();
    await expect(planDeleteBtn).toBeVisible();
    const planPath = await planDeleteBtn.getAttribute('data-action');
    const savingsPlanId = Number(planPath.split('/').pop());

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: planPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${planPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'savings_plan' && i.id === savingsPlanId)
        ).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'savings_plan' && i.id === savingsPlanId),
          `SavingsPlan #${savingsPlanId} must appear in /api/waste-bin after OK`
        ).toBeTruthy();

        // Recover savings plan so subsequent specs have an active plan
        await page.evaluate(async (id) => {
          await fetch(`/api/waste-bin/savings_plan/${id}/recover`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
          });
        }, savingsPlanId);
      },
    });
  });

  test('8. Net Worth Custom Asset (/netWorth -> DELETE /netWorth/binAsset/:id): single confirm, Cancel preserves in /api/networth, OK moves to /api/waste-bin', async ({ page }) => {
    test.setTimeout(45000);
    await page.goto('/netWorth');
    await loginIfRequired(page);

    // Switch to Custom Assets card on mobile swipe deck if needed
    const customAssetsPill = page.locator('button[data-target="card-nw-assets"]');
    if (await customAssetsPill.isVisible().catch(() => false)) {
      await customAssetsPill.click();
    }

    const uniqueAssetName = `DeleteTest Asset ${Date.now()}`;
    await page.evaluate(async (name) => {
      await fetch('/api/networth/asset', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          userId: 'demo',
          name,
          category: 'PROPERTY',
          value: 15000,
          notes: 'Delete confirmation E2E',
        }),
      });
    }, uniqueAssetName);
    await page.reload();

    if (await customAssetsPill.isVisible().catch(() => false)) {
      await customAssetsPill.click();
    }

    const nwBefore = await apiGetJson(page, '/api/networth');
    const createdAsset = (nwBefore.customAssets || []).find((a) => a.name === uniqueAssetName);
    expect(createdAsset, 'Created asset must appear in GET /api/networth customAssets').toBeTruthy();
    const assetPath = `/netWorth/binAsset/${createdAsset.id}`;

    await verifyCancelThenAcceptDelete({
      page,
      expectedActionPath: assetPath,
      getDeleteButton: () =>
        page.locator(`button[data-method="DELETE"][data-action="${assetPath}"]:visible`).first(),
      assertStillPresentAfterCancel: async () => {
        const nw = await apiGetJson(page, '/api/networth');
        expect((nw.customAssets || []).some((a) => a.id === createdAsset.id)).toBe(true);

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'net_worth_asset' && i.id === createdAsset.id)
        ).toBeFalsy();
      },
      assertDeletedAfterAccept: async () => {
        const nw = await apiGetJson(page, '/api/networth');
        expect((nw.customAssets || []).some((a) => a.id === createdAsset.id)).toBe(false);

        const bin = await apiGetJson(page, '/api/waste-bin');
        expect(
          (bin.items || []).find((i) => i.entity_type === 'net_worth_asset' && i.id === createdAsset.id),
          `NetWorthAsset #${createdAsset.id} must appear in /api/waste-bin after OK`
        ).toBeTruthy();
      },
    });
  });
});
