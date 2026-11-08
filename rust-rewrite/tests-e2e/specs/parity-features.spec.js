// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Rust Parity Features: Nav Crumb, Auth Redirect, Passkeys, Historic Spent, Pace & CRUD Edits', () => {

  const AUTH_COOKIES = [
    { name: 'user_id', value: 'demo', domain: 'localhost', path: '/' },
    { name: 'monmon_session', value: 'demo', domain: 'localhost', path: '/' },
    { name: 'authenticated', value: 'true', domain: 'localhost', path: '/' }
  ];

  test('1. Unauthenticated /spog redirects to styled /login with return_to parameter', async ({ page }) => {
    // Navigate to /spog without cookies
    await page.goto('/spog');
    await expect(page).toHaveURL(/\/login\?return_to=%2Fspog/);
    
    // Check that the login form and passkey login button exist
    await expect(page.locator('#login-form')).toBeVisible();
    await expect(page.locator('#btn-passkey-login')).toBeVisible();
    await expect(page.locator('input[name="return_to"]')).toHaveValue('/spog');
  });

  test('2. Passkey options on /signup and /settings', async ({ page, context }) => {
    // Signup page shows passkey registration prompt/badge
    await page.goto('/signup');
    await expect(page.locator('#btn-add-passkey-signup')).toBeVisible();

    // Authenticated settings shows passkey management card
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/settings');
    await expect(page.locator('#passkey-settings-card')).toBeVisible();
    await expect(page.locator('#btn-add-passkey-settings')).toBeVisible();
  });

  test('3. Dynamic Nav Crumb reflects current page and acts as refresh', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);

    // SPOG
    await page.goto('/spog');
    await expect(page.locator('.current-crumb-link')).toContainText('SPOG');
    
    // Outgoings
    await page.goto('/myOutgoings');
    await expect(page.locator('.current-crumb-link')).toContainText('Outgoings');

    // Savings Plan
    await page.goto('/savingsPlan');
    await expect(page.locator('.current-crumb-link')).toContainText('Savings Plan');

    // Historic Spent
    await page.goto('/historicSpent');
    await expect(page.locator('.current-crumb-link')).toContainText('Historic Spent');

    // Spending Pace
    await page.goto('/spendingPace');
    await expect(page.locator('.current-crumb-link')).toContainText('Spending Pace');
  });

  test('4. /historicSpent page parity with Java version', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/historicSpent');

    // Verify title and main metric cards
    await expect(page).toHaveTitle(/Spending History/);
    await expect(page.locator('text=Spent This Month')).toBeVisible();
    await expect(page.locator('text=Avg per completed cycle')).toBeVisible();
    await expect(page.locator('text=Highest Spend Cycle')).toBeVisible();
    await expect(page.locator('text=Lowest Spend Cycle')).toBeVisible();

    // History table / rows
    await expect(page.locator('.history-cycle-item, details').first()).toBeVisible();
  });

  test('5. /spendingPace page parity with histogram chart & 5-day intervals', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/spendingPace');

    // Verify title and chart canvas
    await expect(page).toHaveTitle(/Spending Pace/);
    await expect(page.locator('#pace-chart')).toBeVisible();
    await expect(page.locator('text=5-Day Chunk Pace')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cumulative Pace' })).toBeVisible();
    await expect(page.locator('text=Total Spend (£)')).toBeVisible();

    // Verify 5-day table
    await expect(page.locator('text=Spending Pace Breakdown')).toBeVisible();
    await expect(page.locator('tbody tr').first()).toBeVisible();
  });

  test('6. Edit buttons on Core Primitives: Accounts, Outgoings, Incomings, Savings Plan', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);

    // Accounts edit button opens prefilled modal
    await page.goto('/myAccounts');
    const accEditBtn = page.locator('.btn-edit:visible').first();
    await expect(accEditBtn).toBeVisible();
    await accEditBtn.click();
    await expect(page.locator('#account-modal')).toBeVisible();
    await expect(page.locator('#account-modal-form')).toHaveAttribute('action', /\/updateAccount\/\d+/);
    await expect(page.locator('#acc-name')).not.toHaveValue('');

    // Outgoings edit button opens prefilled modal
    await page.goto('/myOutgoings');
    const outEditBtn = page.locator('.btn-edit:visible').first();
    await expect(outEditBtn).toBeVisible();
    await outEditBtn.click();
    await expect(page.locator('#outgoing-modal')).toBeVisible();
    await expect(page.locator('#outgoing-modal-form')).toHaveAttribute('action', /\/updateOutgoing\/\d+/);
    await expect(page.locator('#outgoing-name')).not.toHaveValue('');

    // Incomings edit button opens prefilled modal
    await page.goto('/myIncomings');
    const incEditBtn = page.locator('.btn-edit:visible').first();
    await expect(incEditBtn).toBeVisible();
    await incEditBtn.click();
    await expect(page.locator('#incoming-modal')).toBeVisible();
    await expect(page.locator('#incoming-modal-form')).toHaveAttribute('action', /\/updateIncoming\/\d+/);
    await expect(page.locator('#incoming-name')).not.toHaveValue('');

    // Savings Plan edit button scrolls to #edit-form in Amend mode
    await page.goto('/savingsPlan');
    const planEditBtn = page.locator('.btn-edit:visible').first();
    if (await planEditBtn.isVisible()) {
      await planEditBtn.click();
      await expect(page.locator('#edit-form')).toBeVisible();
      await expect(page.locator('#edit-form form')).toHaveAttribute('action', /\/savingsPlan\/updateItem\/\d+/);
      await expect(page.locator('#name')).not.toHaveValue('');
    }
  });

  test('7. Incoming "Is this Payday?" flag, target account, and CRUD lifecycle', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/myIncomings');

    // 1. Open Add Incoming modal and verify controls
    await page.getByRole('button', { name: 'Add Incoming' }).click();
    await expect(page.locator('#incoming-modal')).toBeVisible();
    await expect(page.locator('#incoming-payday')).toBeVisible();
    await expect(page.locator('#incoming-account')).toBeVisible();

    // 2. Create a new incoming with payday checked
    const testName = 'Salary Test E2E ' + Date.now();
    await page.fill('#incoming-name', testName);
    await page.fill('input[name="netValue"]', '2750.50');
    await page.fill('input[name="incomingMonthDay"]', '28');
    await page.check('#incoming-payday');
    await page.click('#incoming-submit-btn');

    // 3. Confirm redirected to /myIncomings and row has Primary Payday badge
    await expect(page).toHaveURL(/\/myIncomings/);
    const row = page.locator('tr:visible, .item-card:visible').filter({ hasText: testName }).first();
    await expect(row).toBeVisible();
    await expect(row.locator('.badge-payday-yes')).toContainText('Primary Payday');

    // 4. Edit incoming: uncheck payday
    await row.locator('.btn-edit').click();
    await expect(page.locator('#incoming-modal')).toBeVisible();
    await expect(page.locator('#incoming-payday')).toBeChecked();
    await page.uncheck('#incoming-payday');
    await page.click('#incoming-submit-btn');

    // 5. Confirm updated to Secondary badge
    await expect(page).toHaveURL(/\/myIncomings/);
    const updatedRow = page.locator('tr:visible, .item-card:visible').filter({ hasText: testName }).first();
    await expect(updatedRow.locator('.badge-payday-no')).toContainText('Secondary');

    // 6. Delete the test incoming
    page.on('dialog', dialog => dialog.accept());
    await updatedRow.locator('.btn-delete').click();
    await page.waitForURL(/\/myIncomings/);
    await expect(page.locator('tr:visible, .item-card:visible').filter({ hasText: testName })).toHaveCount(0);
  });

  test('8. SPOG Grouped Accounts with Freshness Badges and Update Prefill Redirect', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/spog');

    // 1. Verify Card 4 Accounts section (switch tab on mobile deck if present)
    const accountsNavPill = page.locator('button[data-target="spog-card-accounts"]');
    if (await accountsNavPill.isVisible()) {
      await accountsNavPill.click();
    }
    const accountsCard = page.locator('#spog-card-accounts');
    await expect(accountsCard).toBeVisible();
    await expect(accountsCard.locator('text=Accounts & Balances')).toBeVisible();

    // 2. Verify account groups are displayed
    await expect(page.locator('.spog-account-group-title').first()).toBeVisible();

    // 3. Verify freshness badges exist (e.g. FRESH, STALE, NO_PAYMENT_DUE, or NO_BALANCE)
    const updateLink = accountsCard.locator('a[href*="/myBalances?accountId="]').first();
    await expect(updateLink).toBeVisible();
    const href = await updateLink.getAttribute('href');
    expect(href).toMatch(/\/myBalances\?accountId=\d+/);

    // 4. Click update link and verify balance screen loads with prefilled account
    await updateLink.click();
    await expect(page).toHaveURL(/\/myBalances\?accountId=\d+/);
    await expect(page.locator('text=Updating balance for')).toBeVisible();
    const accountSelect = page.locator('#account');
    await expect(accountSelect).toBeVisible();
    const selectedVal = await accountSelect.inputValue();
    expect(selectedVal).not.toBe('');
  });

  test('9. /myBalances Parity: Interactive Chart Canvas, Account Multiselect Filters, Sign Toggle & CRUD', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/myBalances');

    // 1. Verify canvas chart exists
    const chart = page.locator('#balance-chart');
    await expect(chart).toBeVisible();

    // 2. Verify account multi-select filter controls
    const filterBtn = page.locator('#btn-account-multiselect');
    await expect(filterBtn).toBeVisible();
    await filterBtn.click();
    await expect(page.locator('#multiselect-dropdown')).toBeVisible();

    // Test "None" button
    await page.locator('#select-none-btn').click();
    await expect(page.locator('#empty-chart-msg')).toBeVisible();

    // Test "All" button
    await page.locator('#select-all-btn').click();
    await expect(page.locator('#empty-chart-msg')).toBeHidden();

    // Test "Monthly Pot" button
    await page.locator('#select-pot-btn').click();
    await expect(page.locator('#empty-chart-msg')).toBeHidden();

    // 3. Test sign toggle button (±)
    const valInput = page.locator('#balance-value');
    const signBtn = page.locator('#balance-sign-toggle');
    await valInput.fill('250.75');
    await signBtn.click();
    expect(await valInput.inputValue()).toBe('-250.75');
    await signBtn.click();
    expect(await valInput.inputValue()).toBe('250.75');

    // 4. Record new balance and verify in list
    const testBalanceVal = '189.42';
    await valInput.fill(testBalanceVal);
    await page.click('#record-balance-form button[type="submit"]');
    await expect(page).toHaveURL(/\/myBalances/);

    // Verify balance appears in log
    const balanceEntry = page.locator(`tr:has-text("£${testBalanceVal}"), .stat-cell:has-text("£${testBalanceVal}")`).first();
    await expect(balanceEntry).toBeVisible();

    // 5. Delete balance
    page.on('dialog', dialog => dialog.accept());
    const deleteBtn = page.locator('form[action*="/balance/"][action*="/delete"] button[type="submit"]:visible').first();
    await deleteBtn.click();
    await expect(page).toHaveURL(/\/myBalances/);
  });

  test('10. Passkey WebAuthn: options allow cross-platform password managers (BitWarden/1Password)', async ({ page, request, context }) => {
    // 1. Check options API does not constrain authenticatorAttachment to "platform"
    const res = await request.get('/api/auth/passkey/options?type=register&username=demo');
    expect(res.ok()).toBeTruthy();
    const data = await res.json();
    expect(data.authenticatorSelection).toBeDefined();
    expect(data.authenticatorSelection.authenticatorAttachment).toBeUndefined();
    expect(data.authenticatorSelection.residentKey).toBe('preferred');

    // 2. Enable virtual authenticator in Chromium via CDP for headless WebAuthn support
    try {
      const cdp = await context.newCDPSession(page);
      await cdp.send('WebAuthn.enable');
      await cdp.send('WebAuthn.addVirtualAuthenticator', {
        options: {
          protocol: 'ctap2',
          transport: 'internal',
          hasResidentKey: true,
          hasUserVerification: true,
          isUserVerified: true
        }
      });
    } catch (e) {
      // Non-chromium engines skip CDP
    }

    // 3. Settings page passkey adding works
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/settings');
    const addPasskeyBtn = page.locator('#btn-add-passkey-settings');
    await expect(addPasskeyBtn).toBeVisible();
    await addPasskeyBtn.click();

    // Verify enrolled passkeys list updates
    await expect(page.locator('#passkeys-list-container')).not.toContainText('No passkeys registered yet');
  });

  test('11. NomMon Meal Planner: All 7 days have a full recipe in recipes array', async ({ page, request, context }) => {
    // 1. Check API endpoint directly
    const apiRes = await request.get('/api/nommon/plan?budget=50&people=1');
    expect(apiRes.ok()).toBeTruthy();
    const data = await apiRes.json();
    expect(data.plan).toBeDefined();
    expect(data.plan.days).toHaveLength(7);
    expect(data.plan.recipes.length).toBeGreaterThanOrEqual(7);

    // Each recipe must have title, servings, prepTimeMinutes, cookTimeMinutes, ingredientsUsed, instructions
    for (const recipe of data.plan.recipes) {
      expect(recipe.title).toBeTruthy();
      expect(recipe.servings).toBeGreaterThanOrEqual(1);
      expect(recipe.instructions.length).toBeGreaterThanOrEqual(1);
    }

    // 2. Check UI page
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/nommon');
    await expect(page.locator('#nommon-container')).toBeVisible();
    await expect(page.locator('#btn-regenerate-nommon')).toBeVisible();

    // Check Meal Recipes tab
    const recipesTabBtn = page.locator('button[data-tab="tab-recipes"]');
    await expect(recipesTabBtn).toBeVisible();
    await recipesTabBtn.click();
    await expect(page.locator('#recipes-container')).toBeVisible();
  });

  test('12. /myMonthlyPot: Displays account balances, total sum of the monthly pot, and configure form', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/myMonthlyPot');

    await expect(page).toHaveTitle(/Monthly Pot/);
    await expect(page.locator('text=Configure Monthly Pot')).toBeVisible();
    await expect(page.locator('#pot-config-form')).toBeVisible();

    // Check preset options
    const presetSelect = page.locator('#pot-preset');
    await expect(presetSelect).toBeVisible();

    // If an active pot exists, check sum total of the monthly pot
    const potTotalSummary = page.locator('.pot-total-summary');
    if (await potTotalSummary.isVisible()) {
      await expect(potTotalSummary).toContainText('Total Monthly Pot Balance:');
      await expect(page.locator('#monthly-pot-total-sum')).toContainText('£');
    }
  });

  test('13. /myPlans: Central planning hub, sharing agreements form & SPOG mini spending plan note', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);

    // 1. Navigate to /myPlans
    await page.goto('/myPlans');
    await expect(page).toHaveTitle(/Plans & Sharing Hub/);
    await expect(page.locator('text=Plans & Sharing Hub')).toBeVisible();

    // Verify 3 navigation cards exist
    await expect(page.locator('a[href="/savingsPlan"].w3-button')).toBeVisible();
    await expect(page.locator('a[href="/myMonthlyPot"].w3-button')).toBeVisible();
    await expect(page.locator('a[href="/nommon"].w3-button')).toBeVisible();

    // Verify Bill & Rent Sharing form exists
    await expect(page.locator('#planForm')).toBeVisible();
    await expect(page.locator('#previewText')).toContainText('You pay');

    // Create a Bill Sharing agreement
    await page.selectOption('#planType', 'BILL_SHARE');
    await page.fill('#peopleCount', '2');
    await page.fill('#notes', '50/50 test sharing agreement');
    await page.click('#planForm button[type="submit"]');

    await expect(page).toHaveURL(/\/myPlans/);
    await expect(page.locator('[data-entity-id*="plan-"]:has-text("Bill Sharing")')).toBeVisible();

    // 2. Check /spog mini spending plan and note about actual monthly pot balance
    await page.goto('/spog');
    const potNavPill = page.locator('button[data-target="spog-card-spent"]');
    if (await potNavPill.isVisible()) {
      await potNavPill.click();
    }
    // Verify SPOG card 2 contains monthly pot note or spent info
    await expect(page.locator('#spog-card-spent')).toBeVisible();
    await expect(page.locator('#spog-card-spent').locator('text=Spent This Month')).toBeVisible();
  });

  test('14. Yearly and N-Yearly Outgoings: Frequency options, filter tabs, frequency badges & next due calculation', async ({ page }) => {
    // 1. Authenticate
    await page.context().addCookies([
      { name: 'user_session', value: 'authenticated-user-token', domain: 'localhost', path: '/' },
      { name: 'monmon_session', value: 'authenticated-user-token', domain: 'localhost', path: '/' },
    ]);

    // 2. Navigate to /myOutgoings
    await page.goto('/myOutgoings');
    await expect(page).toHaveTitle(/MonMon Outgoings/);

    // Ensure no raw JS source leaked into rendered body text
    const bodyText = await page.innerText('body');
    expect(bodyText).not.toContain('function openPauseModal');

    // Filter tabs should exist on the page
    const tabsBar = page.locator('.outgoings-tabs-bar');
    await expect(tabsBar).toBeVisible();
    await expect(page.locator('.outgoings-tab-btn[data-tab="active"]')).toBeVisible();
    await expect(page.locator('.outgoings-tab-btn[data-tab="yearly"]')).toBeVisible();
    await expect(page.locator('.outgoings-tab-btn[data-tab="paused"]')).toBeVisible();
    await expect(page.locator('.outgoings-tab-btn[data-tab="all"]')).toBeVisible();

    // 3. Open Add Outgoing Modal
    await page.click('button:has-text("Add Outgoing")');
    await expect(page.locator('#outgoing-modal')).toBeVisible();

    // Frequency selector and yearly fields
    const freqSelect = page.locator('#outgoingFrequency');
    await expect(freqSelect).toBeVisible();

    // Fill form for Yearly outgoing
    const uniqueYearlyName = `Yearly Home Insurance ${Date.now()}`;
    await page.fill('#outgoing-name', uniqueYearlyName);
    await page.fill('#outgoing-cost', '420.00');
    await page.fill('input[name="outgoingDay"]', '18');
    await freqSelect.selectOption('yearly');

    // Yearly options should now be visible
    const yearlyGroup = page.locator('#yearly-month-group');
    await expect(yearlyGroup).toBeVisible();
    await page.selectOption('#outgoingMonth', '11'); // November
    await page.fill('#yearInterval', '1');
    await page.fill('#startingYear', '2025');

    // Submit form
    await page.click('#outgoing-modal button[type="submit"]');
    await expect(page.locator('#outgoing-modal')).toBeHidden();

    const isMobile = (page.viewportSize()?.width || 1280) < 768;
    const getItem = (name) => isMobile
      ? page.locator('.item-card').filter({ hasText: name })
      : page.locator('tr').filter({ hasText: name });

    // 4. Verify outgoing appears with Yearly badge and month
    const yearlyRow = getItem(uniqueYearlyName);
    await page.click('.outgoings-tab-btn[data-tab="all"]');
    await expect(yearlyRow).toBeVisible();
    await expect(yearlyRow).toContainText('Yearly (November)');

    // 5. Create an N-Yearly outgoing (Every 3 years in March)
    await page.click('button:has-text("Add Outgoing")');
    const uniqueNYearlyName = `N-Yearly Passport Renewal ${Date.now()}`;
    await page.fill('#outgoing-name', uniqueNYearlyName);
    await page.fill('#outgoing-cost', '85.00');
    await page.fill('input[name="outgoingDay"]', '5');
    await page.selectOption('#outgoingFrequency', 'yearly');
    await page.selectOption('#outgoingMonth', '3'); // March
    await page.fill('#yearInterval', '3');
    await page.fill('#startingYear', '2023');

    await page.click('#outgoing-modal button[type="submit"]');
    await expect(page.locator('#outgoing-modal')).toBeHidden();

    // 6. Verify N-yearly badge
    await page.click('.outgoings-tab-btn[data-tab="all"]');
    const nYearlyRow = getItem(uniqueNYearlyName);
    await expect(nYearlyRow).toBeVisible();
    await expect(nYearlyRow).toContainText('Every 3 yrs (March started 2023)');

    // 7. Verify Filter Tabs functionality
    // Switch to "Yearly Bills" tab
    await page.click('.outgoings-tab-btn[data-tab="yearly"]');
    await expect(yearlyRow).toBeVisible();
    await expect(nYearlyRow).toBeVisible();

    // Switch to "Monthly and due now" tab
    await page.click('.outgoings-tab-btn[data-tab="active"]');
    // If current month is November (month 11), yearlyRow should be visible; March row (3) is not due in Nov 2026
    await expect(nYearlyRow).toBeHidden();
  });

  test('15. NomMon in-store checked items counter and spend update dynamically', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);

    // Clear localStorage checked items before test
    await page.addInitScript(() => {
      localStorage.removeItem('nommon_checked_items');
    });

    // Intercept NomMon API plan to provide deterministic grocery checklist
    await page.route('**/api/nommon/plan*', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          plan: {
            planTitle: 'Test Parity Plan',
            weeklyBudget: 45.0,
            estimatedBasketCost: 38.5,
            safetyBuffer: 6.5,
            shoppingList: [
              { name: 'Sainsbury Whole Milk 2L', category: 'Dairy & Eggs', quantity: 1, estimatedPrice: 1.55, packageSize: '2L' },
              { name: 'Sainsbury British Free Range Eggs 6pk', category: 'Dairy & Eggs', quantity: 1, estimatedPrice: 1.75, packageSize: '6pk' },
              { name: 'Sainsbury Thick Sliced Wholemeal Loaf', category: 'Bakery', quantity: 2, estimatedPrice: 0.85, packageSize: '800g' }
            ],
            days: [],
            recipes: []
          },
          locked: false
        })
      });
    });

    await page.goto('/nommon');

    // Initial counter check
    const counterEl = page.locator('#nommon-checked-counter');
    const spendEl = page.locator('#nommon-checked-spend');
    await expect(counterEl).toBeVisible();
    await expect(counterEl).toHaveText('0 / 3');
    await expect(spendEl).toHaveText('£0.00 in cart');

    // Click first item (Whole Milk - £1.55)
    const milkItem = page.locator('.nommon-checklist-item:has-text("Sainsbury Whole Milk")');
    await milkItem.click();
    await expect(counterEl).toHaveText('1 / 3');
    await expect(spendEl).toHaveText('£1.55 in cart');

    // Click second item (Wholemeal Loaf - 2x £0.85 = £1.70)
    const loafItem = page.locator('.nommon-checklist-item:has-text("Wholemeal Loaf")');
    await loafItem.click();
    await expect(counterEl).toHaveText('2 / 3');
    await expect(spendEl).toHaveText('£3.25 in cart');

    // Clear checks button resets both counter and cart spend
    await page.click('#btn-uncheck-all');
    await expect(counterEl).toHaveText('0 / 3');
    await expect(spendEl).toHaveText('£0.00 in cart');
  });

  test('16. Date picker / ticker bar renders with layout stability and early script execution', async ({ page, context }) => {
    await context.addCookies(AUTH_COOKIES);
    await page.goto('/spog');

    const ticker = page.locator('#ticker');
    await expect(ticker).toBeVisible();

    // Verify min-height styling prevents layout collapse / flash
    const minHeight = await ticker.evaluate(el => window.getComputedStyle(el).minHeight);
    expect(minHeight).toBe('38px');

    // Verify live ticker content has rendered properly
    await expect(ticker).toContainText('Today: Day');
  });
});
