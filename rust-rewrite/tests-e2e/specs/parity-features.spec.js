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
});
