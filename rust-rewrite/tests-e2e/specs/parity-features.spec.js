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
});
