// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('New User Flow, Existing User Flow & Floating Action Buttons', () => {

  test('Welcome and Landing page matches the complete public design and feature set', async ({ page }) => {
    // 1. Visit Welcome/Landing Page
    const res = await page.goto('/');
    expect(res?.status()).toBe(200);

    // 2. SEO title matches Java landing page
    await expect(page).toHaveTitle(/MonMon — Intelligent Single-Pane-of-Glass Personal Finance & NomMon Meal Planning/);

    // 3. Hero section headlines and action buttons
    const heroTitle = page.locator('h1').first();
    await expect(heroTitle).toContainText(/Master your cashflow/i);

    await expect(page.locator('.hero-cta-group a[href="/signup"]')).toBeVisible();
    await expect(page.locator('.hero-cta-group a[href="/login"]')).toBeVisible();
    await expect(page.locator('.hero-cta-group a[href="#what-is-nommon"]')).toBeVisible();

    // 4. Feature showcase sections
    await expect(page.locator('h2:has-text("What is MonMon?")')).toBeVisible();
    await expect(page.locator('h2:has-text("What is NomMon?")')).toBeVisible();
    await expect(page.locator('h2:has-text("Meet Ed")')).toBeVisible();

    // 5. Theme toggle button on landing page works
    const themeBtn = page.locator('#landing-theme-toggle, #theme-toggle').first();
    await expect(themeBtn).toBeVisible();
    const htmlEl = page.locator('html');
    const initialTheme = await htmlEl.getAttribute('data-theme');
    await themeBtn.click();
    const newTheme = await htmlEl.getAttribute('data-theme');
    expect(newTheme).not.toEqual(initialTheme);
  });

  test('New user sign up flow shows onboarding hints modal AND logging out terminates session', async ({ page }) => {
    // 1. Visit Landing Page
    await page.goto('/');
    await expect(page).toHaveTitle(/MonMon/);

    // 2. Click Sign Up button
    const signupBtn = page.locator('#btn-landing-signup, #btn-hero-signup').first();
    await expect(signupBtn).toBeVisible();
    await signupBtn.click();

    // 3. Lands on /signup page
    expect(page.url()).toContain('/signup');
    await expect(page.locator('#signup-username')).toBeVisible();

    // 4. Fill in new unique user credentials
    const uniqueUser = `newuser_${Date.now()}`;
    await page.fill('#signup-username', uniqueUser);
    await page.fill('#signup-email', `${uniqueUser}@example.com`);
    await page.fill('#signup-password', 'SecretPass123!');

    // 5. Submit registration form
    await page.click('#signup-submit-btn');

    // 6. Redirected to /spog
    await page.waitForURL('**/spog**');
    expect(page.url()).toContain('/spog');

    // 7. Verify onboarding hints modal pops up for fresh account with 0 bank accounts
    const hintsModal = page.locator('#fresh-account-modal');
    await expect(hintsModal).toBeVisible({ timeout: 5000 });
    await expect(hintsModal).toContainText('Welcome to MonMon!');
    await expect(hintsModal).toContainText('Add Bank Accounts');
    await expect(hintsModal).toContainText('Add Income & Payday');
    await expect(hintsModal).toContainText('Add Regular Outgoings');
    await expect(hintsModal).toContainText('Assign Daily Pot & Savings Plan');

    // 8. Dismiss modal
    const dismissBtn = page.locator('#fresh-modal-dismiss-btn');
    await expect(dismissBtn).toBeVisible();
    await dismissBtn.click();
    await expect(hintsModal).not.toBeVisible();

    // 9. Open Navigation Drawer and perform Logout
    const navToggle = page.locator('#nav-toggle');
    await expect(navToggle).toBeVisible();
    await navToggle.click();

    const logoutLink = page.locator('.drawer-logout a[href="/centralLogout"]');
    await expect(logoutLink).toBeVisible();
    await logoutLink.click();

    // 10. Verify redirect back to Landing page
    await page.waitForURL(url => url.pathname === '/' || url.pathname === '');
    expect(page.url()).not.toContain('/spog');

    // 11. Verify session is destroyed: accessing protected route redirects to login / auth provider
    await page.goto('/spog');
    expect(page.url()).toMatch(/auth|login/);
  });

  test('Existing user sign in flow does NOT show hints modal AND logging out terminates session', async ({ page }) => {
    // 1. Visit Landing Page
    await page.goto('/');

    // 2. Click Log In button
    const loginBtn = page.locator('#btn-landing-login, #btn-hero-login').first();
    await expect(loginBtn).toBeVisible();
    await loginBtn.click();

    // 3. Lands on /login page
    expect(page.url()).toContain('/login');
    await expect(page.locator('#login-username, #username').first()).toBeVisible();

    // 4. Submit known credentials for seeded user 'demo'
    await page.fill('#login-username, #username', 'demo');
    await page.fill('#login-password, #password', 'demo123');
    await page.click('#login-submit-btn, #kc-login');

    // 5. Redirected to /spog
    await page.waitForURL('**/spog**');
    expect(page.url()).toContain('/spog');

    // 6. Verify hints modal is NOT present
    await expect(page.locator('#fresh-account-modal')).toHaveCount(0);

    // 7. Seeded cards and metrics are visible
    const metricsGrid = page.locator('.spog-layout-grid, #spog-card-pot').first();
    await expect(metricsGrid).toBeVisible();

    // 8. Open Drawer and perform Logout
    const navToggle = page.locator('#nav-toggle');
    await expect(navToggle).toBeVisible();
    await navToggle.click();

    const logoutLink = page.locator('.drawer-logout a[href="/centralLogout"]');
    await expect(logoutLink).toBeVisible();
    await logoutLink.click();

    // 9. Verify redirect back to Landing page
    await page.waitForURL(url => url.pathname === '/' || url.pathname === '');
    expect(page.url()).not.toContain('/spog');

    // 10. Verify session is destroyed: accessing protected route redirects to login / auth provider
    await page.goto('/myAccounts');
    expect(page.url()).toMatch(/auth|login/);
  });

  test('SPOG floating buttons: Can I Afford This? and Ask Ed float on bottom right', async ({ page }) => {
    await page.goto('/spog?userId=demo');

    // 1. Verify Can I Afford This FAB exists and floats at bottom right
    const affordFab = page.locator('#btn-afford-fab');
    await expect(affordFab).toBeVisible();
    await expect(affordFab).toContainText('Can I Afford This?');

    // 2. Verify Ask Ed FAB exists and floats at bottom right
    const edFab = page.locator('#btn-ed-fab');
    await expect(edFab).toBeVisible();

    // 3. Test Can I Afford This modal
    await affordFab.click();
    const affordModal = page.locator('#afford-modal');
    await expect(affordModal).toBeVisible();

    await page.fill('#afford-cost-input', '35.00');
    await expect(page.locator('#afford-result-box')).toBeVisible();
    await expect(page.locator('#afford-verdict-banner')).toContainText(/YES|NO/);

    await page.click('#afford-close-btn');
    await expect(affordModal).not.toBeVisible();

    // 4. Test Ask Ed modal
    await edFab.click();
    const edModal = page.locator('#ed-modal');
    await expect(edModal).toBeVisible();
    await expect(page.locator('.ed-says-content')).toBeVisible();
    await expect(page.locator('.ed-says-content')).not.toBeEmpty();

    await page.click('#ed-close-btn');
    await expect(edModal).not.toBeVisible();
  });

  test('Outgoings and Net Worth floating Ask Ed button and Debt-to-Asset ratio', async ({ page, context }) => {
    // Authenticate context session for protected views
    const baseURL = test.info().project.use.baseURL || 'http://localhost:9001';
    await context.addCookies([
      { name: 'user_id', value: 'demo', url: baseURL },
      { name: 'monmon_session', value: 'demo', url: baseURL },
      { name: 'authenticated', value: 'true', url: baseURL },
    ]);

    // 1. Outgoings page: Ask Ed is visible at bottom right, Afford FAB is NOT present
    await page.goto('/myOutgoings?userId=demo');
    const edFabOutgoings = page.locator('#btn-ed-fab');
    await expect(edFabOutgoings).toBeVisible();
    await expect(page.locator('#btn-afford-fab')).toHaveCount(0);

    await edFabOutgoings.click();
    const edOutgoingsModal = page.locator('#ed-modal');
    await expect(edOutgoingsModal).toBeVisible();
    await edOutgoingsModal.locator('#ed-close-btn').click();
    await expect(edOutgoingsModal).not.toBeVisible();

    // 2. Net Worth page: Ask Ed is visible at bottom right
    await page.goto('/netWorth?userId=demo');
    const edFabNetWorth = page.locator('#btn-ed-fab');
    await expect(edFabNetWorth).toBeVisible();

    // 3. Verify Debt-to-Asset ratio is displayed as a decimal and percentage
    const pageText = await page.locator('body').innerText();
    expect(pageText).toMatch(/Debt(\/| to )Asset Ratio/i);
    // Matches format like 0.XX (XX.X%) or 0.00 (0.0% — Debt Free)
    expect(pageText).toMatch(/\d+\.\d{2}\s*\(\d+\.\d+%/);

    // 4. Ask Ed on Net Worth
    await edFabNetWorth.click({ force: true });
    const edModal = page.locator('#ed-modal');
    await expect(edModal).toBeVisible();
    await expect(edModal.locator('.ed-says-content')).toContainText(/Ed|net worth|wealth/i);
    await edModal.locator('#ed-close-btn').click();
    await expect(edModal).not.toBeVisible();
  });

});
